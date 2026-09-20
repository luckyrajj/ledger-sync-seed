package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import in.simplifymoney.ledgersync.ingest.TxnIdentity;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.bson.Document;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MongoDocumentStore implements DocumentStore, AutoCloseable {

    private final MongoClient client;
    private final MongoDatabase db;
    private final MongoCollection<Document> txns;
    private final MongoCollection<Document> totals;

    public MongoDocumentStore(String connectionString) {
        this.client = MongoClients.create(connectionString);
        this.db = client.getDatabase("ledger");
        this.txns = db.getCollection("transactions");
        this.totals = db.getCollection("category_totals");

        // Create indexes
        this.txns.createIndex(Indexes.descending("occurredAt", "accountLast4"));
        this.txns.createIndex(Indexes.ascending("sourceMessageIds"));
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = month.atEndOfMonth().atTime(23, 59, 59, 999999999).atOffset(ZoneOffset.UTC);

        List<NormalizedTxn> result = new ArrayList<>();
        for (Document doc : txns.find(
                Filters.and(
                        Filters.eq("accountLast4", accountLast4),
                        Filters.gte("occurredAt", start.toInstant()),
                        Filters.lte("occurredAt", end.toInstant())
                )).sort(Indexes.descending("occurredAt"))) {
            result.add(fromDocument(doc));
        }
        return result;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> result = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            result.put(c, BigDecimal.ZERO.setScale(2));
        }

        Document doc = totals.find(Filters.eq("_id", accountLast4)).first();
        if (doc != null) {
            Document totalsDoc = (Document) doc.get("totals");
            if (totalsDoc != null) {
                for (Category c : Category.values()) {
                    Decimal128 d = totalsDoc.get(c.name(), Decimal128.class);
                    if (d != null) {
                        result.put(c, d.bigDecimalValue().setScale(2));
                    }
                }
            }
        }
        return result;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document doc = txns.find(Filters.in("sourceMessageIds", messageId)).first();
        if (doc == null) {
            return Optional.empty();
        }
        return Optional.of(fromDocument(doc));
    }

    @Override
    public void save(NormalizedTxn txn) {
        String id = TxnIdentity.of(txn.accountLast4(), txn.occurredAt(), txn.direction(), txn.amount());

        Document doc = new Document("_id", id)
                .append("accountLast4", txn.accountLast4())
                .append("occurredAt", txn.occurredAt().toInstant())
                .append("direction", txn.direction().name())
                .append("amount", new Decimal128(txn.amount()))
                .append("category", txn.category().name())
                .append("merchant", txn.merchant())
                .append("sourceMessageIds", txn.sourceMessageIds());

        // Upsert the transaction
        var updateResult = txns.updateOne(
                Filters.eq("_id", id),
                new Document("$setOnInsert", doc),
                new UpdateOptions().upsert(true)
        );

        // If it was inserted (not a duplicate), update category totals
        if (updateResult.getUpsertedId() != null) {
            totals.updateOne(
                    Filters.eq("_id", txn.accountLast4()),
                    Updates.inc("totals." + txn.category().name(), new Decimal128(txn.amount())),
                    new UpdateOptions().upsert(true)
            );
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private NormalizedTxn fromDocument(Document doc) {
        return new NormalizedTxn(
                doc.getString("accountLast4"),
                OffsetDateTime.ofInstant(doc.getDate("occurredAt").toInstant(), ZoneOffset.UTC),
                Direction.valueOf(doc.getString("direction")),
                doc.get("amount", Decimal128.class).bigDecimalValue(),
                Category.valueOf(doc.getString("category")),
                doc.getString("merchant"),
                doc.getList("sourceMessageIds", String.class)
        );
    }
}
