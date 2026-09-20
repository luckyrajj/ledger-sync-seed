package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HDFC Bank SMS (sender: AD-HDFCBK-S).
 *
 * Three transactional shapes in corpus-a:
 *
 *  V1 — single-sentence debit/credit on a savings account:
 *    "Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN.
 *     Avl Bal: Rs.92,213.10."
 *    "Rs.45,000.00 credited to a/c **4821 on 01-07-26 at 09:02 by SALARY CREDIT.
 *     Avl Bal: Rs.93,211.40"
 *
 *  V2 — multiline Sent / Received format (rolled out mid-window):
 *    "Sent INR40.00\nTo: UPI/XEROX\nOn: 26 Jul 26 07:29\nA/c: XX4821\n
 *     Available Balance: INR 39302.03\n-HDFC Bank"
 *    "Received INR780.49\nFrom: NEFT INWARD\nOn: 26 Jul 26 22:54\nA/c: XX4821\n
 *     Available Balance: INR 39894.51\n-HDFC Bank"
 *
 *  CARD — credit-card spend:
 *    "Rs 1,249.99 spent on HDFC Bank Card x3310 at BLINKIT on 03-07-26 11:51.
 *     Avl Limit: Rs.196,250.03."
 *
 * Non-transactional shapes that must return Optional.empty():
 *  - Balance enquiry: "Avl Bal in a/c **XXXX is Rs.NNN as on DD-MM-YY."
 *  - OTP: "268880 is your OTP for txn of Rs.NNN..."
 */
public final class HdfcSmsParser implements MessageParser {

    public static final String SENDER = "AD-HDFCBK-S";

    // V1: single-sentence debit / credit on a savings account.
    private static final Pattern V1 = Pattern.compile(
            "(?<dir>debited from|credited to) a/c \\*\\*(?<acct>\\d{4}) "
                    + "on (?<when>\\d{2}-\\d{2}-\\d{2} at \\d{2}:\\d{2}) "
                    + "(?:to|by) (?<merchant>[^.]+)\\.");

    // V2: multiline Sent/Received format.
    private static final Pattern V2 = Pattern.compile(
            "^(?<dir>Sent|Received) .*?\\n(?:To|From): (?<merchant>.+?)\\n"
                    + "On: (?<when>\\d{2} \\w{3} \\d{2} \\d{2}:\\d{2})\\n"
                    + "A/c: XX(?<acct>\\d{4})",
            Pattern.DOTALL);

    // CARD: credit-card spend.
    private static final Pattern CARD = Pattern.compile(
            "spent on HDFC Bank Card x(?<acct>\\d{4}) at (?<merchant>.+?) "
                    + "on (?<when>\\d{2}-\\d{2}-\\d{2} \\d{2}:\\d{2})\\.");

    // Non-transactional: balance-enquiry reply — must be rejected explicitly.
    private static final Pattern BALANCE_ENQUIRY = Pattern.compile(
            "Avl Bal in a/c \\*\\*\\d{4} is", Pattern.CASE_INSENSITIVE);

    // Non-transactional: OTP message.
    private static final Pattern OTP = Pattern.compile(
            "is your OTP for txn", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        // Reject non-transactional shapes up front.
        if (BALANCE_ENQUIRY.matcher(body).find()) return Optional.empty();
        if (OTP.matcher(body).find()) return Optional.empty();

        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            Direction d = v1.group("dir").startsWith("debited")
                    ? Direction.DEBIT : Direction.CREDIT;
            return build(m, v1.group("acct"),
                    v1.group("when").replace(" at ", " "),
                    d, v1.group("merchant"));
        }

        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            Direction d = "Sent".equals(v2.group("dir"))
                    ? Direction.DEBIT : Direction.CREDIT;
            return build(m, v2.group("acct"), v2.group("when"),
                    d, v2.group("merchant"));
        }

        Matcher card = CARD.matcher(body);
        if (card.find()) {
            return build(m, card.group("acct"), card.group("when"),
                    Direction.DEBIT, card.group("merchant"));
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(RawMessage m, String acct, String when,
                                      Direction dir, String merchant) {
        // Use transactionAmount() — strips the balance section first.
        // This is the INC-2026-09-11 fix: prevents "Avl Bal: Rs.92,213.10"
        // from being returned as the transaction amount when the real amount
        // is a whole-rupee value like "Rs.5".
        BigDecimal amount = Amounts.transactionAmount(m.body());
        OffsetDateTime at = Dates.ist(when);
        if (amount == null || at == null) return Optional.empty();
        return Optional.of(new ParsedTxn(acct, at, dir, amount,
                merchant.trim(), Amounts.statedBalance(m.body()), m.messageId()));
    }
}
