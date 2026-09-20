package in.simplifymoney.ledgersync;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodProcess;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.MongodConfig;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.runtime.Network;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

public class MongoMetricsTest {

    private static MongodExecutable mongodExecutable;
    private static MongodProcess mongod;
    private static MongoClient mongoClient;
    
    @BeforeAll
    static void setup() throws Exception {
        MongodStarter starter = MongodStarter.getDefaultInstance();
        String bindIp = "localhost";
        int port = 27018;
        MongodConfig mongodConfig = MongodConfig.builder()
            .version(Version.Main.PRODUCTION)
            .net(new Net(bindIp, port, Network.localhostIsIPv6()))
            .build();
            
        mongodExecutable = starter.prepare(mongodConfig);
        mongod = mongodExecutable.start();
        
        mongoClient = MongoClients.create("mongodb://localhost:27018");
        MongoDatabase db = mongoClient.getDatabase("ledger");
        
        db.getCollection("transactions").createIndex(new Document("accountLast4", 1).append("occurredAt", -1));
        db.getCollection("transactions").createIndex(new Document("sourceMessageIds", 1));
        
        System.out.println("Inserting 100,000 transactions...");
        var coll = db.getCollection("transactions");
        var catColl = db.getCollection("category_totals");
        
        java.util.List<Document> batch = new java.util.ArrayList<>();
        for (int i = 0; i < 100000; i++) {
            String account = i % 2 == 0 ? "4821" : "9075";
            OffsetDateTime date = OffsetDateTime.of(2025, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                                     .plusMinutes(i * 10L); 
                                     
            Document doc = new Document("_id", "txn-" + i)
                    .append("accountLast4", account)
                    .append("occurredAt", date.toInstant().toEpochMilli()) 
                    .append("amount", "10.00")
                    .append("sourceMessageIds", List.of("m-" + i));
            batch.add(doc);
            
            if (batch.size() == 5000) {
                coll.insertMany(batch);
                batch.clear();
            }
        }
        
        catColl.insertOne(new Document("_id", "4821").append("total", 50000));
    }
    
    @AfterAll
    static void teardown() {
        if (mongoClient != null) mongoClient.close();
        if (mongod != null) mongod.stop();
        if (mongodExecutable != null) mongodExecutable.stop();
    }
    
    @Test
    void executeQueriesAndGetMetrics() {
        MongoDatabase db = mongoClient.getDatabase("ledger");
        
        // Query 1: one account's transactions for one month, newest first
        Document q1 = new Document("find", "transactions")
                .append("filter", new Document("accountLast4", "4821")
                        .append("occurredAt", new Document("$gte", YearMonth.of(2025, 1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
                                .append("$lte", YearMonth.of(2025, 1).atEndOfMonth().atTime(23, 59, 59, 999999999).toInstant(ZoneOffset.UTC).toEpochMilli())))
                .append("sort", new Document("occurredAt", -1));
        
        Document explain1 = db.runCommand(new Document("explain", q1).append("verbosity", "executionStats"));
        printMetrics("Query 1", explain1);
        
        // Query 2: running totals per category for an account
        Document q2 = new Document("find", "category_totals")
                .append("filter", new Document("_id", "4821"));
                
        Document explain2 = db.runCommand(new Document("explain", q2).append("verbosity", "executionStats"));
        printMetrics("Query 2", explain2);
        
        // Query 3: which transaction did it produce
        Document q3 = new Document("find", "transactions")
                .append("filter", new Document("sourceMessageIds", "m-50000"));
                
        Document explain3 = db.runCommand(new Document("explain", q3).append("verbosity", "executionStats"));
        printMetrics("Query 3", explain3);
    }
    
    private void printMetrics(String name, Document explainResult) {
        Document executionStats = (Document) explainResult.get("executionStats");
        System.out.println("=== " + name + " ===");
        if (executionStats != null) {
            System.out.println("totalDocsExamined: " + executionStats.getInteger("totalDocsExamined"));
            System.out.println("nReturned: " + executionStats.getInteger("nReturned"));
        } else {
            System.out.println(explainResult.toJson());
        }
    }
}
