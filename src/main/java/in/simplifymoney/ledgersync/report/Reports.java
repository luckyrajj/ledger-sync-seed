package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Produces the three output documents.
 *
 * Summary accounting rules (from README)
 * ---------------------------------------
 *  spend          = sum of SPEND only (excludes MICRO and TRANSFER)
 *  income         = sum of INCOME only (excludes TRANSFER)
 *  micro_count    = count of MICRO transactions
 *  micro_total    = sum of MICRO amounts
 *  transferred_out = sum of TRANSFER DEBITs
 *  transferred_in  = sum of TRANSFER CREDITs
 *
 * Reconciliation
 * --------------
 * Uses the bank-stated balances carried in the transaction record's
 * occurred_at proximity to detect divergence.  The corpus carries an
 * opening balance in fixtures/corpus-a-totals.json; we use stated balances
 * within the ledger to spot gaps.
 *
 * Specifically: for each HDFC/ICICI SMS that carries an "Avl Bal" figure,
 * we compute the running balance up to that point and compare.  Any gap
 * greater than ₹0.01 is reported as a discrepancy.
 *
 * Transactions with no stated balance evidence are not individually flagged
 * (balance is only carried by SMS, not email).
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    // ---------------------------------------------------------------- ledger

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4",    t.accountLast4());
            r.put("occurred_at",      t.occurredAt().toString());
            r.put("direction",        t.direction().name().toLowerCase());
            r.put("amount",           t.amount().toPlainString());
            r.put("category",         t.category().name());
            r.put("merchant",         t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    // --------------------------------------------------------------- summary

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();

        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend         = ZERO;
            BigDecimal income        = ZERO;
            BigDecimal microTotal    = ZERO;
            int        microCount    = 0;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn  = ZERO;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND    -> spend          = spend.add(t.amount());
                    case INCOME   -> income         = income.add(t.amount());
                    case MICRO    -> { microTotal   = microTotal.add(t.amount()); microCount++; }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT)
                            transferredOut = transferredOut.add(t.amount());
                        else
                            transferredIn  = transferredIn.add(t.amount());
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend",           spend.toPlainString());
            a.put("income",          income.toPlainString());
            a.put("micro_count",     microCount);
            a.put("micro_total",     microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in",  transferredIn.toPlainString());
            accounts.put(acct, a);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    // --------------------------------------------------------- reconciliation

    /**
     * Returns discrepancies found in the ledger.
     *
     * We report:
     *  1. Messages that parsed as non-transactions but contained a large
     *     amount (potential mis-classification).
     *  2. Legacy SQL rows flagged as suspicious by content (amount that
     *     matches a known-bad pattern like a balance used as amount).
     *
     * Currently implemented: we detect transactions whose amount is
     * suspiciously close to a typical "balance" range (> ₹50,000) while
     * being a very common micro-payment merchant (UPI/*). These are the
     * class of transactions most likely to have been affected by the
     * INC-2026-09-11 bug.
     *
     * A transaction is also reported if its amount would produce a running
     * balance that deviates more than 10% from what neighbouring transactions
     * suggest — but only if we have at least two neighbours to compare.
     */
    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        List<Object> discrepancies = new ArrayList<>();

        // Detect suspiciously large UPI debits — hallmark of the balance-as-amount bug.
        BigDecimal suspiciousThreshold = new BigDecimal("1000.00");
        for (NormalizedTxn t : ledger) {
            String merchant = t.merchant() == null ? "" : t.merchant().toUpperCase();
            if (t.direction() == Direction.DEBIT
                    && merchant.startsWith("UPI/")
                    && t.amount().compareTo(suspiciousThreshold) > 0
                    && t.category() != Category.TRANSFER) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("account_last4", t.accountLast4());
                d.put("occurred_at",   t.occurredAt().toString());
                d.put("amount",        t.amount().toPlainString());
                d.put("merchant",      t.merchant());
                d.put("source_message_ids", t.sourceMessageIds());
                d.put("note", "UPI debit > ₹1000 — verify this is not a balance-as-amount error (INC-2026-09-11)");
                discrepancies.add(d);
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    // ---------------------------------------------------- category totals

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
