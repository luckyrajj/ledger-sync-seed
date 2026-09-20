package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Assigns exactly one Category to a parsed transaction.
 *
 * Rules (in precedence order):
 *
 *  1. TRANSFER — a leg of the user moving money between their OWN accounts.
 *     Detected by merchant keywords that indicate inter-account movement:
 *     IMPS/P2A (only when the same payee appears on both accounts) or
 *     NEFT INWARD SELF.
 *
 *     Note: UPI/P2P/REFUND and REVERSAL are NOT transfers — they are refunds
 *     or reversals back to the user, classified as INCOME.
 *
 *     Note: IMPS/P2A to a third party (e.g. RAHUL SHARMA if they are not one
 *     of the user's accounts) is SPEND, not TRANSFER. We detect this by
 *     checking whether a corresponding credit to one of the user's accounts
 *     appears in the ledger (done in TransferLinker, post-deduplication).
 *
 *     This categorizer applies only PRELIMINARY classification. TransferLinker
 *     upgrades SPEND/INCOME to TRANSFER after seeing the full ledger.
 *
 *  2. MICRO — a UPI debit of ≤ ₹100 (threshold is inclusive).
 *     Merchant starts with "UPI/" and amount ≤ 100.00 and direction is DEBIT.
 *
 *  3. INCOME — any remaining CREDIT.
 *
 *  4. SPEND — any remaining DEBIT.
 */
public final class Categorizer {

    private static final BigDecimal MICRO_THRESHOLD = new BigDecimal("100.00");

    /** UPI payment prefix — used for MICRO detection. Matches both UPI/ and UPI (no slash). */
    private static final Pattern UPI_PREFIX = Pattern.compile(
            "^UPI", Pattern.CASE_INSENSITIVE);

    private Categorizer() {}

    public static Category categorize(ParsedTxn txn) {
        return categorize(txn.direction(), txn.amount(), txn.merchant());
    }

    public static Category categorize(Direction direction, BigDecimal amount,
                                      String merchant) {
        if (direction == Direction.CREDIT) {
            return Category.INCOME;
        }

        // direction == DEBIT
        // MICRO: UPI debit ≤ ₹100.
        String m = merchant == null ? "" : merchant.trim();
        if (UPI_PREFIX.matcher(m).find()
                && amount.compareTo(MICRO_THRESHOLD) <= 0) {
            return Category.MICRO;
        }

        return Category.SPEND;
    }
}
