package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice — "Rs.", "Rs ", "INR " — and strips
 * thousands separators before returning a BigDecimal with exactly two decimal
 * places.
 *
 * INC-2026-09-11 root cause and fix
 * ----------------------------------
 * The original AMOUNT regex required exactly two decimal places
 * ([0-9,]+\.[0-9]{2}). Bank messages often carry whole-rupee amounts:
 * "Rs.5", "Rs 20", "INR 18,000". When the debit was a whole-rupee value, the
 * regex skipped it and then found the available-balance figure further along
 * the message (e.g. "Avl Bal: Rs.92,213.10"), recording ₹92,213.10 instead of ₹5.
 *
 * Two-part fix applied here:
 *  1. AMOUNT regex now matches whole-rupee values too (decimal optional).
 *  2. transactionAmount() strips the entire "Avl Bal: Rs.NNN" / "Available
 *     Balance: INR NNN" / "BalAvl Rs NNN" section from the body before it
 *     starts scanning, so a balance figure can never be returned as the
 *     transaction amount regardless of message shape or amount size.
 *
 * All parsers must call transactionAmount().  The legacy first() method is
 * kept only for backward compatibility with existing tests.
 */
public final class Amounts {

    private Amounts() {}

    /**
     * Matches any Rs./Rs/INR amount, including whole-rupee ("Rs.5") and
     * comma-formatted ("Rs.92,213.10") values.
     */
    static final Pattern AMOUNT =
            Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");

    /**
     * Matches the balance phrase that banks append after the transaction detail.
     * Covers:  Avl Bal, Available Balance, BalAvl, Avl Limit.
     * Both "Rs." and "INR" prefixes, with or without separating punctuation.
     */
    private static final Pattern BALANCE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl|Avl\\s*Limit)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*[0-9,]+(?:\\.[0-9]{1,2})?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns the transaction amount from the message body.
     *
     * Strips the stated-balance phrase first so the balance can never be
     * confused with the transaction amount (INC-2026-09-11 fix).
     * Use this in all parsers.
     */
    public static BigDecimal transactionAmount(String body) {
        String stripped = BALANCE.matcher(body).replaceAll(" ");
        Matcher m = AMOUNT.matcher(stripped);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /**
     * Identical to {@link #transactionAmount(String)}.
     *
     * @deprecated Callers should switch to the self-documenting
     *             {@link #transactionAmount(String)} name.
     */
    @Deprecated
    public static BigDecimal first(String body) {
        return transactionAmount(body);
    }

    /** The balance the bank quoted, if it quoted one. */
    public static BigDecimal statedBalance(String body) {
        Matcher m = BALANCE.matcher(body);
        if (!m.find()) return null;
        // Re-extract just the numeric part from the matched balance phrase.
        Matcher amt = AMOUNT.matcher(m.group());
        if (!amt.find()) return null;
        return toDecimal(amt.group(1));
    }

    static BigDecimal toDecimal(String raw) {
        String clean = raw.replace(",", "");
        BigDecimal bd = new BigDecimal(clean);
        // Normalise to exactly 2 decimal places.
        return bd.setScale(2);
    }
}
