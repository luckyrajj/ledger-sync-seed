package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS (sender: VM-ICICIB-T).
 *
 * Two transactional shapes found in corpus-a:
 *
 *  V1 — "Dear Customer" style:
 *    "Dear Customer, Acct XX9075 is debited with INR 22.50 on 01/07/2026 10:22.
 *     Info: UPI/VEGETABLE VENDOR. Avl Bal Rs.31,882.25 -ICICI Bank"
 *    Also "debited with Rs.25" (no decimal) and "credited with INR 18,000"
 *    (no decimal, comma-formatted).
 *
 *  V2 — "ICICI Bank Acct Dr/Cr" style (appears late in corpus):
 *    "ICICI Bank Acct XX9075 Dr INR 75.00 on 25-Jul-2026 16:20;
 *     UPI/STATIONERY ref no 531325779660. BalAvl Rs 52,681.30"
 *    "ICICI Bank Acct XX9075 Cr INR 4,200 on 27-Jul-2026 13:05;
 *     MYNTRA REFUND ref no 501111458099. BalAvl Rs 52,005.13"
 *
 * Promotional messages from VM-ICICIB-T (loan offers, etc.) return empty —
 * they contain no "Acct XX" pattern, so both regexes will fail naturally.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    // V1: "Dear Customer, Acct XXNNNN is debited/credited with INR/Rs.NNN on ..."
    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    // V2: "ICICI Bank Acct XXNNNN Dr/Cr INR NNN on DD-Mon-YYYY HH:MM; MERCHANT ref no NNN."
    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) .*? "
                    + "on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.+?) ref no");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            Direction d = "debited".equals(v1.group("dir"))
                    ? Direction.DEBIT : Direction.CREDIT;
            BigDecimal amount = Amounts.transactionAmount(body);
            OffsetDateTime at = Dates.ist(v1.group("when"));
            if (amount == null || at == null) return Optional.empty();
            return Optional.of(new ParsedTxn(v1.group("acct"), at, d, amount,
                    v1.group("merchant").trim(),
                    Amounts.statedBalance(body), m.messageId()));
        }

        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            Direction d = "Dr".equals(v2.group("dir"))
                    ? Direction.DEBIT : Direction.CREDIT;
            BigDecimal amount = Amounts.transactionAmount(body);
            OffsetDateTime at = Dates.ist(v2.group("when"));
            if (amount == null || at == null) return Optional.empty();
            return Optional.of(new ParsedTxn(v2.group("acct"), at, d, amount,
                    v2.group("merchant").trim(),
                    Amounts.statedBalance(body), m.messageId()));
        }

        return Optional.empty();
    }
}
