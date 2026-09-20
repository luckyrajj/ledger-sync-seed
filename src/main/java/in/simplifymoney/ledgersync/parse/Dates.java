package in.simplifymoney.ledgersync.parse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Bank SMS carry a local date and time and no timezone.  The customer, the
 * bank and the branch are all in India, so these are IST.
 *
 * Formats found in corpus-a:
 *   DD-MM-YY HH:mm     — HDFC V1 SMS  ("04-07-26 07:19")
 *   DD/MM/YYYY HH:mm   — ICICI V1 SMS ("01/07/2026 10:22")
 *   DD MMM YY HH:mm    — HDFC V2 SMS  ("26 Jul 26 07:29")
 *   DD-MMM-YYYY HH:mm  — ICICI V2 SMS ("25-Jul-2026 16:20")
 *
 * Email Date headers may carry an explicit timezone offset (+0530 or +0000).
 * These are parsed via OffsetDateTime.parse() in the email parser directly.
 */
public final class Dates {

    private Dates() {}

    public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private static final List<DateTimeFormatter> SMS_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yy HH:mm",     Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm",   Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yy HH:mm",    Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm",  Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yy HH:mm",    Locale.ENGLISH));

    /** Parse a local date-time written by a bank, as IST. */
    public static OffsetDateTime ist(String dateAndTime) {
        if (dateAndTime == null) return null;
        for (DateTimeFormatter f : SMS_FORMATS) {
            try {
                return LocalDateTime.parse(dateAndTime.trim(), f).atOffset(IST);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }
}
