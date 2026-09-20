package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.TxnIdentity;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        // 1. Get deduplicated SQL transactions
        List<NormalizedTxn> sqlAll = sql.all();
        Map<String, NormalizedTxn> sqlDeduplicated = new LinkedHashMap<>();
        for (NormalizedTxn t : sqlAll) {
            String key = TxnIdentity.of(t.accountLast4(), t.occurredAt(), t.direction(), t.amount());
            if (!sqlDeduplicated.containsKey(key)) {
                sqlDeduplicated.put(key, t);
            } else {
                NormalizedTxn existing = sqlDeduplicated.get(key);
                List<String> mergedIds = new ArrayList<>(existing.sourceMessageIds());
                for (String id : t.sourceMessageIds()) {
                    if (!mergedIds.contains(id)) {
                        mergedIds.add(id);
                    }
                }
                mergedIds.sort(String::compareTo);
                String bestMerchant = existing.merchant();
                if (bestMerchant == null || bestMerchant.isEmpty()) {
                    bestMerchant = t.merchant();
                }
                NormalizedTxn merged = new NormalizedTxn(
                        existing.accountLast4(),
                        existing.occurredAt(),
                        existing.direction(),
                        existing.amount(),
                        existing.category(),
                        bestMerchant,
                        mergedIds
                );
                sqlDeduplicated.put(key, merged);
            }
        }

        // 2. Fetch all from Document Store by month for each account
        TreeSet<String> accounts = new TreeSet<>();
        TreeSet<YearMonth> months = new TreeSet<>();
        
        for (NormalizedTxn t : sqlDeduplicated.values()) {
            accounts.add(t.accountLast4());
            months.add(YearMonth.from(t.occurredAt().atZoneSameInstant(ZoneOffset.UTC)));
        }

        Map<String, NormalizedTxn> docStoreMap = new LinkedHashMap<>();
        for (String account : accounts) {
            for (YearMonth month : months) {
                List<NormalizedTxn> docs = documents.forAccountMonth(account, month);
                for (NormalizedTxn d : docs) {
                    String key = TxnIdentity.of(d.accountLast4(), d.occurredAt(), d.direction(), d.amount());
                    docStoreMap.put(key, d);
                }
            }
        }

        // 3. Compare them
        for (Map.Entry<String, NormalizedTxn> entry : sqlDeduplicated.entrySet()) {
            String key = entry.getKey();
            NormalizedTxn sqlTxn = entry.getValue();
            NormalizedTxn docTxn = docStoreMap.get(key);

            if (docTxn == null) {
                divergences.add(new Divergence(
                        "Missing transaction in Document Store",
                        sqlTxn.toString(),
                        "null"
                ));
            } else {
                if (sqlTxn.category() != docTxn.category()) {
                    divergences.add(new Divergence(
                            "Category mismatch for " + key,
                            sqlTxn.category().name(),
                            docTxn.category().name()
                    ));
                }
                if (!sqlTxn.sourceMessageIds().equals(docTxn.sourceMessageIds())) {
                    divergences.add(new Divergence(
                            "Source messages mismatch for " + key,
                            sqlTxn.sourceMessageIds().toString(),
                            docTxn.sourceMessageIds().toString()
                    ));
                }
            }
        }

        for (Map.Entry<String, NormalizedTxn> entry : docStoreMap.entrySet()) {
            if (!sqlDeduplicated.containsKey(entry.getKey())) {
                divergences.add(new Divergence(
                        "Extra transaction in Document Store",
                        "null",
                        entry.getValue().toString()
                ));
            }
        }

        return divergences;
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
