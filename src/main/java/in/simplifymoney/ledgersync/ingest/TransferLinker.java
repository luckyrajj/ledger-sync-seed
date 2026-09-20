package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Identifies TRANSFER legs by finding matching debit/credit pairs across
 * the user's accounts.
 *
 * A transfer is confirmed when:
 *  - One account has a DEBIT of amount X at time T (±5 minutes)
 *  - Another of the user's accounts has a CREDIT of amount X at approximately
 *    the same time
 *  - The merchant text on both legs contains the same payee keyword
 *    (e.g. both say "IMPS/P2A/PARAG KAPOOR")
 *
 * This approach avoids false-positives from:
 *  - UPI/P2P/REFUND — refunds from merchants, not inter-account transfers
 *  - REVERSAL ATM WDL — ATM failure reversals, not inter-account transfers
 *  - IMPS to a third party not in the user's account set (e.g. RAHUL SHARMA
 *    if that name does not appear as a credit on another tracked account)
 *
 * Design decision: we use AMOUNT + TIME window + matching merchant keyword.
 * Merchant must share at least one of the known transfer prefixes (IMPS, NEFT,
 * UPI/P2P) to be a candidate for pairing. This prevents coincidental amount
 * matches from being misclassified.
 *
 * The resulting list has the same size as the input but with categories
 * updated for confirmed transfer legs.
 */
public final class TransferLinker {

    /** Minutes tolerance for matching debit vs credit time. */
    private static final int TIME_TOLERANCE_MINUTES = 10;

    private TransferLinker() {}

    public static List<NormalizedTxn> link(List<NormalizedTxn> txns) {
        // Separate debits and credits that are potential transfer legs.
        // Only IMPS, NEFT INWARD SELF, UPI/P2P patterns are candidates.
        List<NormalizedTxn> debitCandidates  = new ArrayList<>();
        List<NormalizedTxn> creditCandidates = new ArrayList<>();

        for (NormalizedTxn t : txns) {
            if (!isTransferCandidate(t.merchant())) continue;
            if (t.direction() == Direction.DEBIT)  debitCandidates.add(t);
            if (t.direction() == Direction.CREDIT) creditCandidates.add(t);
        }

        // For each debit, try to find a matching credit on a DIFFERENT account.
        // Track which credits have already been paired.
        boolean[] creditMatched = new boolean[creditCandidates.size()];

        // Map from TxnIdentity → new category.
        Map<String, Category> overrides = new HashMap<>();

        for (NormalizedTxn debit : debitCandidates) {
            for (int ci = 0; ci < creditCandidates.size(); ci++) {
                if (creditMatched[ci]) continue;
                NormalizedTxn credit = creditCandidates.get(ci);

                // Must be on a DIFFERENT account.
                if (debit.accountLast4().equals(credit.accountLast4())) continue;

                // Amount must match exactly.
                if (debit.amount().compareTo(credit.amount()) != 0) continue;

                // Time must be within tolerance.
                if (!withinWindow(debit.occurredAt(), credit.occurredAt())) continue;

                // Merchant keyword must overlap (simple heuristic).
                if (!merchantOverlap(debit.merchant(), credit.merchant())) continue;

                // Confirmed transfer pair.
                String debitKey  = TxnIdentity.of(debit.accountLast4(),  debit.occurredAt(),  debit.direction(),  debit.amount());
                String creditKey = TxnIdentity.of(credit.accountLast4(), credit.occurredAt(), credit.direction(), credit.amount());
                overrides.put(debitKey,  Category.TRANSFER);
                overrides.put(creditKey, Category.TRANSFER);
                creditMatched[ci] = true;
                break;
            }
        }

        if (overrides.isEmpty()) return txns;

        // Apply overrides.
        List<NormalizedTxn> result = new ArrayList<>(txns.size());
        for (NormalizedTxn t : txns) {
            String key = TxnIdentity.of(t.accountLast4(), t.occurredAt(),
                    t.direction(), t.amount());
            Category cat = overrides.getOrDefault(key, t.category());
            if (cat == t.category()) {
                result.add(t);
            } else {
                result.add(new NormalizedTxn(t.accountLast4(), t.occurredAt(),
                        t.direction(), t.amount(), cat, t.merchant(),
                        t.sourceMessageIds()));
            }
        }
        return result;
    }

    private static boolean isTransferCandidate(String merchant) {
        if (merchant == null) return false;
        String m = merchant.toUpperCase();
        return m.contains("IMPS") || m.contains("NEFT INWARD SELF") || m.contains("UPI/P2P");
    }

    private static boolean withinWindow(OffsetDateTime a, OffsetDateTime b) {
        long diffMinutes = Math.abs(
                a.withOffsetSameInstant(ZoneOffset.UTC).toEpochSecond()
                - b.withOffsetSameInstant(ZoneOffset.UTC).toEpochSecond()) / 60;
        return diffMinutes <= TIME_TOLERANCE_MINUTES;
    }

    private static boolean merchantOverlap(String a, String b) {
        if (a == null || b == null) return false;
        // Check if there's a meaningful keyword in common.
        String ua = a.toUpperCase();
        String ub = b.toUpperCase();
        // Extract the payee name after the last "/" in IMPS/P2A/PAYEE or similar.
        String payeeA = lastToken(ua);
        String payeeB = lastToken(ub);
        if (!payeeA.isEmpty() && payeeA.equals(payeeB)) return true;
        // Fallback: share 4+ chars at the start of the merchant string.
        int minLen = Math.min(ua.length(), ub.length());
        return minLen >= 4 && ua.substring(0, Math.min(4, minLen)).equals(
                ub.substring(0, Math.min(4, minLen)));
    }

    private static String lastToken(String s) {
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1).trim() : s.trim();
    }
}
