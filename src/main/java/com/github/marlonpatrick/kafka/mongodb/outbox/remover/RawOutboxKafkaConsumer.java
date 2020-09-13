package com.github.marlonpatrick.kafka.mongodb.outbox.remover;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Updates.pull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.UpdateOneModel;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonDocument;
import org.bson.Document;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class RawOutboxKafkaConsumer {

    private MongoClient mongoClient;

    RawOutboxKafkaConsumer(MongoClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    @KafkaListener(topicPattern = "${outbox.remover.kafka.consumer.topic.pattern}")
    void onMessage(List<ConsumerRecord<String, String>> records) throws Exception {

        Map<String, List<UpdateOneModel<? extends Document>>> updatesByCollection = new HashMap<>();

        for (ConsumerRecord<String, String> record : records) {

            BsonDocument recordValueDocument = recordValueToBsonDocument(record);

            List<UpdateOneModel<? extends Document>> updates = getCollectionUpdatesList(updatesByCollection,
                    recordValueDocument);

            updates.add(buildUpdateOperation(recordValueDocument));
        }

        for (Map.Entry<String, List<UpdateOneModel<? extends Document>>> updatesEntry : updatesByCollection
                .entrySet()) {

            String[] splitedKey = updatesEntry.getKey().split("\\.");

            String databaseName = splitedKey[0];

            String collectionName = splitedKey[1];

            BulkWriteResult bulkUpdateResult = performBulkUpdate(databaseName, collectionName, updatesEntry.getValue());

            System.out.println("bulkUpdateResult:");
            System.out.println(updatesEntry.getKey());
            System.out.println(bulkUpdateResult.getMatchedCount());
            System.out.println(bulkUpdateResult.getModifiedCount());
            System.out.println(bulkUpdateResult.getInsertedCount());
            System.out.println(bulkUpdateResult.getDeletedCount());
        }
    }

    private BulkWriteResult performBulkUpdate(String databaseName, String collectionName,
            List<UpdateOneModel<? extends Document>> updateOperations) {

        MongoDatabase mongoDatabase = mongoClient.getDatabase(databaseName);

        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);

        return collection.bulkWrite(updateOperations, new BulkWriteOptions().ordered(false));
    }

    private UpdateOneModel<? extends Document> buildUpdateOperation(BsonDocument recordValueDocument) {
        BsonBinary documentKey = getDocumentKey(recordValueDocument);

        List<BsonBinary> outboxIds = getOutboxIds(recordValueDocument);

        return new UpdateOneModel<>(eq("_id", documentKey), pull("outbox", in("_id", outboxIds)));
    }

    private List<BsonBinary> getOutboxIds(BsonDocument recordValueDocument) {
        BsonArray outbox = recordValueDocument.getDocument("fullDocument").getArray("outbox");

        return outbox.stream().map(bv -> bv.asDocument().getBinary("_id"))
                .collect(Collectors.toList());                
    }

    private BsonBinary getDocumentKey(BsonDocument recordValueDocument) {
        return recordValueDocument.getDocument("documentKey").getBinary("_id");
    }

    private List<UpdateOneModel<? extends Document>> getCollectionUpdatesList(
            Map<String, List<UpdateOneModel<? extends Document>>> operationsByCollection,
            BsonDocument recordValueDocument) {

        String databaseName = getDatabaseName(recordValueDocument);

        String collectionName = getCollectionName(recordValueDocument);

        String updateMapKey = databaseName + "." + collectionName;

        List<UpdateOneModel<? extends Document>> operations = operationsByCollection.get(updateMapKey);

        if (operations == null) {
            operations = new ArrayList<>();
            operationsByCollection.put(updateMapKey, operations);
        }

        return operations;
    }

    private String getCollectionName(BsonDocument recordValueDocument) {
        return recordValueDocument.getDocument("ns").getString("coll").getValue();
    }

    private String getDatabaseName(BsonDocument recordValueDocument) {
        return recordValueDocument.getDocument("ns").getString("db").getValue();
    }

    private BsonDocument recordValueToBsonDocument(ConsumerRecord<String, String> record) {
        return BsonDocument.parse(record.value().toString().replaceAll("\\p{C}", ""));
    }
}
