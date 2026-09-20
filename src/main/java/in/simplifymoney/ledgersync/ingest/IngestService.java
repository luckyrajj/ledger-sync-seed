package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts deduplicated transactions in the
 * ledger.
 *
 * Two-pass design
 * ---------------
 * Pass 1: Parse every message.  Collect all ParsedTxn instances.
 * Pass 2: Deduplicate via TxnIdentity key.  Each unique real transaction
 *         produces exactly one NormalizedTxn.  This is idempotent.
 *
 * Idempotency guarantee
 * ---------------------
 * Before saving, we check whether the store already contains a transaction
 * with the same TxnIdentity key.  If it does, we skip it.  This ensures
 * that running ingest on the same corpus twice (or on overlapping corpora)
 * produces the same ledger state.
 *
 * Hostile / malformed input
 * -------------------------
 * Exceptions from individual message parsers are caught and logged.  One bad
 * message cannot crash the full ingestion pipeline.  The message is recorded
 * in the Stats as "errored".
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        return ingest(messages);
    }

    public Stats ingest(List<RawMessage> messages) {
        // Pass 1: parse all messages safely.
        List<ParsedTxn> candidates = new ArrayList<>();
        int skipped = 0;
        int errored = 0;

        for (RawMessage m : messages) {
            try {
                Optional<ParsedTxn> p = parsers.parse(m);
                if (p.isEmpty()) {
                    skipped++;
                } else {
                    candidates.add(p.get());
                }
            } catch (Exception ex) {
                // Hostile or malformed input — skip the message, never crash.
                errored++;
                System.err.println("WARN parse error msg=" + m.messageId()
                        + " sender=" + m.sender() + " : " + ex.getMessage());
            }
        }

        // Pass 2: deduplicate.
        List<NormalizedTxn> deduplicated = Deduplicator.deduplicate(candidates);

        // Pass 2b: identify and link transfer pairs across accounts.
        deduplicated = TransferLinker.link(deduplicated);


        // Pass 3: save only those not already in the store.
        Set<String> existing = existingKeys();
        int written = 0;
        for (NormalizedTxn txn : deduplicated) {
            String key = TxnIdentity.of(txn.accountLast4(), txn.occurredAt(),
                    txn.direction(), txn.amount());
            if (!existing.contains(key)) {
                store.save(txn);
                written++;
            }
        }

        return new Stats(messages.size(), written, skipped, errored,
                candidates.size(), deduplicated.size());
    }

    /**
     * Reads all TxnIdentity keys already in the store so we can skip
     * re-saving them (idempotency).
     */
    private Set<String> existingKeys() {
        Set<String> keys = new HashSet<>();
        for (NormalizedTxn t : store.all()) {
            keys.add(TxnIdentity.of(t.accountLast4(), t.occurredAt(),
                    t.direction(), t.amount()));
        }
        return keys;
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                try {
                    Map<String, Object> o = Json.parseObject(line);
                    out.add(new RawMessage(
                            (String) o.get("message_id"),
                            (String) o.get("channel"),
                            (String) o.get("sender"),
                            OffsetDateTime.parse((String) o.get("received_at")),
                            (String) o.get("device_id"),
                            (String) o.get("body")));
                } catch (Exception e) {
                    System.err.println("WARN could not read corpus line: " + e.getMessage());
                }
            }
        }
        return out;
    }

    public record Stats(
            int messagesRead,
            int transactionsWritten,
            int messagesSkipped,
            int messagesErrored,
            int parsedCandidates,
            int afterDedup) {

        // Backward-compatible accessor for SelfCheck.
        public int transactionsWritten() { return transactionsWritten; }
    }
}
