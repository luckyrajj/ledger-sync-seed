package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.DocumentStore;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import in.simplifymoney.ledgersync.store.LedgerStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class AuditTests {

    @Test
    void testIngestIdempotency() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        Path corpus = Path.of("fixtures/corpus-a.jsonl");

        // First ingest
        IngestService.Stats stats1 = ingest.ingestFile(corpus);
        int initialSize = store.all().size();

        // Second ingest (should be idempotent)
        IngestService.Stats stats2 = ingest.ingestFile(corpus);
        int finalSize = store.all().size();

        assertEquals(initialSize, finalSize, "Re-ingesting the same corpus should not create duplicate transactions.");
        assertTrue(initialSize > 0, "Should have ingested some transactions");
    }

    @Test
    void testBackfillIdempotency() {
        MockLedgerStore sqlStore = new MockLedgerStore();
        MockDocStore docStore = new MockDocStore();

        NormalizedTxn txn1 = createTxn("1111", "100.00", "m-1");
        NormalizedTxn txn2 = createTxn("1111", "100.00", "m-2"); 
        
        sqlStore.add(txn1);
        sqlStore.add(txn2); 

        Backfill backfill = new Backfill(sqlStore, docStore);
        Backfill.Result result1 = backfill.run();
        
        assertEquals(2, result1.read());
        assertEquals(1, result1.written(), "Should have written only 1 document after deduplication");
        assertEquals(1, result1.skipped());
        
        // Run backfill again
        Backfill.Result result2 = backfill.run();
        
        assertEquals(2, result2.read());
        assertEquals(1, result2.written()); 
        
        assertEquals(1, docStore.txns.size(), "Document store should still only contain 1 unique transaction");
        assertEquals(2, docStore.txns.get(0).sourceMessageIds().size(), "Source message IDs should be merged");
    }

    @Test
    void testConsistencyCheckerDeliberateModification() {
        MockLedgerStore sqlStore = new MockLedgerStore();
        MockDocStore docStore = new MockDocStore();

        NormalizedTxn txn1 = createTxn("2222", "50.00", "m-10");
        sqlStore.add(txn1);
        docStore.save(txn1);
        
        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> cleanResult = checker.check();
        assertTrue(cleanResult.isEmpty(), "Should have 0 divergences when in sync");
        
        // Let's modify category
        NormalizedTxn alteredCategory = new NormalizedTxn(
                txn1.accountLast4(),
                txn1.occurredAt(),
                txn1.direction(),
                txn1.amount(),
                Category.INCOME, // Altered category! Original is SPEND
                txn1.merchant(),
                txn1.sourceMessageIds()
        );
        docStore.txns.clear();
        docStore.save(alteredCategory);
        
        List<ConsistencyChecker.Divergence> categoryResult = checker.check();
        assertEquals(1, categoryResult.size());
        assertTrue(categoryResult.get(0).what().contains("Category mismatch"));
    }

    private NormalizedTxn createTxn(String account, String amount, String msgId) {
        return new NormalizedTxn(
                account,
                OffsetDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                Direction.DEBIT,
                new BigDecimal(amount),
                Category.SPEND,
                "MERCHANT",
                List.of(msgId)
        );
    }

    static class MockLedgerStore implements LedgerStore {
        List<NormalizedTxn> data = new ArrayList<>();
        void add(NormalizedTxn t) { data.add(t); }
        @Override public void save(NormalizedTxn txn) { data.add(txn); }
        @Override public List<NormalizedTxn> all() { return data; }
        @Override public long count() { return data.size(); }
        public void close() {}
    }

    static class MockDocStore implements DocumentStore {
        List<NormalizedTxn> txns = new ArrayList<>();
        @Override public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) { return new ArrayList<>(txns); }
        @Override public Map<Category, BigDecimal> categoryTotals(String accountLast4) { return new HashMap<>(); }
        @Override public Optional<NormalizedTxn> byMessageId(String messageId) { return Optional.empty(); }
        @Override public void save(NormalizedTxn txn) {
            txns.removeIf(t -> in.simplifymoney.ledgersync.ingest.TxnIdentity.of(t.accountLast4(), t.occurredAt(), t.direction(), t.amount())
                    .equals(in.simplifymoney.ledgersync.ingest.TxnIdentity.of(txn.accountLast4(), txn.occurredAt(), txn.direction(), txn.amount())));
            txns.add(txn);
        }
    }
}
