package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A deterministic, stable identity for one real-world bank transaction.
 *
 * Design rationale
 * ----------------
 * A real transaction is identified by four facts the bank always tells us:
 *   1. Which account was affected (accountLast4)
 *   2. When the transaction occurred, to the minute (occurredAt, minute-truncated)
 *   3. Which direction money moved (direction)
 *   4. How much (amount, normalised to 2 dp)
 *
 * We intentionally exclude the merchant name because:
 *  - The SMS and the email for the same transaction sometimes use slightly
 *    different merchant strings (e.g. "NEFT INWARD SELF" vs "NEFT INWARD").
 *  - Merchant is not part of the bank's canonical transaction record.
 *
 * We truncate to the minute (not second) because bank SMS timestamps are
 * only minute-precision; a matching email might differ by a few seconds if
 * the bank's mail server adds a small delay.
 *
 * The resulting string is used as the document store _id and as the SQL
 * deduplication key.  It must remain stable across re-ingest runs.
 */
public final class TxnIdentity {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmm", Locale.ROOT);

    private TxnIdentity() {}

    /**
     * Returns a stable string key for a parsed transaction.
     * Format: {@code ACCT|YYYYMMDDTHHMM|DIR|AMOUNT}
     * Example: {@code 4821|20260704T0719|DEBIT|5.00}
     */
    public static String of(ParsedTxn p) {
        return of(p.accountLast4(), p.occurredAt(), p.direction(), p.amount());
    }

    public static String of(String accountLast4, OffsetDateTime occurredAt,
                            Direction direction, BigDecimal amount) {
        // Normalise to IST and truncate to minute precision.
        OffsetDateTime ist = occurredAt.withOffsetSameInstant(
                ZoneOffset.ofHoursMinutes(5, 30));
        String ts = ist.format(FMT);
        return accountLast4 + "|" + ts + "|"
                + direction.name() + "|" + amount.toPlainString();
    }
}
