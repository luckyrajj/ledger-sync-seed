package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Both HDFC (alerts@hdfcbank.net) and ICICI (alerts@icicibank.com) use the
 * same email template shape found in corpus-a:
 *
 *   Date: Wed, 01 Jul 2026 09:02:00 +0530
 *   Subject: Transaction alert on your account
 *
 *   Dear Customer,
 *
 *   Your account ending 4821 has been credited with INR 45,000.
 *   Merchant / Remarks: SALARY CREDIT
 *   Transaction reference: 1597155421
 *
 * Variations seen:
 *  - "INR 45,000." (no decimals)          — whole-rupee credit
 *  - "Rs.76.49."                           — with decimals
 *  - "INR 2499.50."                        — no thousands separator
 *  - "Rs.2,499.50."                        — with thousands separator
 *  - Date timezone: "+0530" or "+0000"     — m-00131 has +0000 (UTC)
 *
 * The email is EVIDENCE for an already-occurring transaction.  The bank sent
 * the SMS at the same time; both carry the same occurred_at timestamp.
 * Deduplication (in IngestService / Deduplicator) merges the two.
 *
 * Non-transactional emails return Optional.empty().
 */
public final class EmailParser implements MessageParser {

    private static final Pattern ACCOUNT =
            Pattern.compile("account ending (\\d{4})");

    private static final Pattern DIRECTION_AMOUNT = Pattern.compile(
            "has been (debited|credited) with (?:INR|Rs\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)");

    private static final Pattern MERCHANT =
            Pattern.compile("Merchant / Remarks:\\s*(.+)");

    /** Email Date header formats — may carry an explicit UTC offset. */
    private static final List<DateTimeFormatter> EMAIL_FORMATS = List.of(
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss Z",      Locale.ENGLISH));

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        // Must be a transaction alert.
        Matcher dirAmt = DIRECTION_AMOUNT.matcher(body);
        if (!dirAmt.find()) return Optional.empty();

        // Account number.
        Matcher acctM = ACCOUNT.matcher(body);
        if (!acctM.find()) return Optional.empty();
        String acct = acctM.group(1);

        // Direction and amount.
        Direction dir = "debited".equals(dirAmt.group(1))
                ? Direction.DEBIT : Direction.CREDIT;
        BigDecimal amount;
        try {
            amount = Amounts.toDecimal(dirAmt.group(2));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        // Merchant.
        Matcher merchM = MERCHANT.matcher(body);
        String merchant = merchM.find() ? merchM.group(1).trim() : "";

        // Occurred-at from the email Date header.
        OffsetDateTime occurredAt = parseEmailDate(body);
        if (occurredAt == null) return Optional.empty();

        return Optional.of(new ParsedTxn(acct, occurredAt, dir, amount,
                merchant, null, m.messageId()));
    }

    private OffsetDateTime parseEmailDate(String body) {
        // Extract the Date: header line.
        Pattern dateLine = Pattern.compile("^Date:\\s*(.+)$", Pattern.MULTILINE);
        Matcher dm = dateLine.matcher(body);
        if (!dm.find()) return null;
        String raw = dm.group(1).trim();

        for (DateTimeFormatter fmt : EMAIL_FORMATS) {
            try {
                return OffsetDateTime.parse(raw, fmt);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }
}
