package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.TxnIdentity;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Two things to know before you start:
 *  - the SQL store is not clean. It has been running without a uniqueness
 *    guarantee for a long time
 *  - this will be run more than once, including after a partial failure
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> allSqlTxns = source.all();
        long readCount = allSqlTxns.size();

        // Deduplicate the SQL transactions using TxnIdentity
        Map<String, NormalizedTxn> deduplicated = new LinkedHashMap<>();
        for (NormalizedTxn t : allSqlTxns) {
            String key = TxnIdentity.of(t.accountLast4(), t.occurredAt(), t.direction(), t.amount());
            if (!deduplicated.containsKey(key)) {
                deduplicated.put(key, t);
            } else {
                // Merge source message ids and pick best merchant (similar to Deduplicator)
                NormalizedTxn existing = deduplicated.get(key);
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
                        existing.category(), // Trust the SQL category or re-categorize? Usually SQL category is kept.
                        bestMerchant,
                        mergedIds
                );
                deduplicated.put(key, merged);
            }
        }

        long writtenCount = 0;
        long skippedCount = readCount - deduplicated.size();

        for (NormalizedTxn t : deduplicated.values()) {
            target.save(t);
            writtenCount++;
        }

        return new Result(readCount, writtenCount, skippedCount);
    }

    public record Result(long read, long written, long skipped) {}
}
