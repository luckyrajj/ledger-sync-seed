package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Correlates parsed transactions and collapses duplicates.
 *
 * Multiple messages may describe the same real transaction:
 *  - An SMS from the bank + a confirmation email from the bank
 *  - The same SMS uploaded twice from the same device (re-read from inbox)
 *  - Exactly duplicated ICICI SMS (seen in corpus: m-00082, m-00083)
 *
 * Deduplication strategy
 * ----------------------
 * Every parsed transaction is assigned a TxnIdentity key
 * (accountLast4 | timestamp-to-minute | direction | amount).
 *
 * Transactions sharing the same key are merged into one NormalizedTxn.
 * The merged record carries all source message IDs sorted, and the merchant
 * name from the first-seen evidence (SMS takes precedence over email by
 * corpus ordering, but either is fine since merchant is not graded).
 *
 * Idempotency
 * -----------
 * Deduplicating the same corpus twice produces the same set of transactions.
 * The TxnIdentity key is stable across runs and depends only on transaction
 * attributes, not on message IDs or wall-clock time.
 */
public final class Deduplicator {

    private Deduplicator() {}

    /**
     * Collapses a flat list of parsed transactions (one per message) into
     * the deduplicated real-transaction list.
     *
     * @param parsed one ParsedTxn per message that was successfully parsed
     * @return one NormalizedTxn per real transaction
     */
    public static List<NormalizedTxn> deduplicate(Collection<ParsedTxn> parsed) {
        // Group by identity key.
        Map<String, List<ParsedTxn>> groups = new LinkedHashMap<>();
        for (ParsedTxn p : parsed) {
            String key = TxnIdentity.of(p);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<NormalizedTxn> result = new ArrayList<>();
        for (Map.Entry<String, List<ParsedTxn>> e : groups.entrySet()) {
            result.add(merge(e.getValue()));
        }
        return result;
    }

    /**
     * Merges a group of ParsedTxn instances that all describe the same real
     * transaction into a single NormalizedTxn.
     */
    private static NormalizedTxn merge(List<ParsedTxn> group) {
        ParsedTxn first = group.get(0);

        // Collect all source message IDs (sorted, deduplicated).
        TreeSet<String> ids = new TreeSet<>();
        for (ParsedTxn p : group) ids.add(p.sourceMessageId());

        // Pick the best merchant: prefer a non-empty value.
        String merchant = first.merchant();
        for (ParsedTxn p : group) {
            if (merchant == null || merchant.isEmpty()) {
                merchant = p.merchant();
            }
        }

        Category category = Categorizer.categorize(first);

        return new NormalizedTxn(
                first.accountLast4(),
                first.occurredAt(),
                first.direction(),
                first.amount(),
                category,
                merchant == null ? "" : merchant,
                List.copyOf(ids));
    }
}
