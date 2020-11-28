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
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class RawOutboxKafkaConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RawOutboxKafkaConsumer.class);

    private MongoClient mongoClient;

    RawOutboxKafkaConsumer(MongoClient mongoClient) {
        this.mongoClient = mongoClient;
    }

    @KafkaListener(topicPattern = "${outbox.remover.kafka.consumer.topic.pattern}")
    void onMessage(List<ConsumerRecord<String, String>> records) {

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

            performBulkUpdate(databaseName, collectionName, updatesEntry.getValue());
        }
    }

    private void performBulkUpdate(String databaseName, String collectionName,
            List<UpdateOneModel<? extends Document>> updateOperations) {

        if(LOGGER.isDebugEnabled()){
            LOGGER.debug("Performing bulk update in {}.{} with {} operations.", databaseName, collectionName, updateOperations.size());
        }

        MongoDatabase mongoDatabase = mongoClient.getDatabase(databaseName);

        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);

        BulkWriteResult bulkUpdateResult = collection.bulkWrite(updateOperations, new BulkWriteOptions().ordered(false));

        if(LOGGER.isDebugEnabled()){
            StringBuilder bulkUpdateResultLog = new StringBuilder("Bulk update result: [");
            bulkUpdateResultLog.append("Database: ");
            bulkUpdateResultLog.append(databaseName);
            bulkUpdateResultLog.append(", Collection: ");
            bulkUpdateResultLog.append(collectionName);
            bulkUpdateResultLog.append(", Bulk Update Size: ");
            bulkUpdateResultLog.append(updateOperations.size());
            bulkUpdateResultLog.append(", Matched Count: ");
            bulkUpdateResultLog.append(bulkUpdateResult.getMatchedCount());
            bulkUpdateResultLog.append(", Modified Count: ");
            bulkUpdateResultLog.append(bulkUpdateResult.getModifiedCount());
            bulkUpdateResultLog.append("]");
            LOGGER.debug(bulkUpdateResultLog.toString());
        }
    }

    private UpdateOneModel<? extends Document> buildUpdateOperation(BsonDocument recordValueDocument) {
        BsonValue documentKey = getDocumentKey(recordValueDocument);

        List<BsonValue> outboxIds = getOutboxIds(recordValueDocument);

        return new UpdateOneModel<>(eq("_id", documentKey), pull("outbox", in("_id", outboxIds)));
    }

    private List<BsonValue> getOutboxIds(BsonDocument recordValueDocument) {

        BsonArray outbox = null;

        if(recordValueDocument.getString("operationType").getValue().equalsIgnoreCase("update")){
            outbox = recordValueDocument.getDocument("updateDescription").getDocument("updatedFields").getArray("outbox");
        }else{
            outbox = recordValueDocument.getDocument("fullDocument").getArray("outbox");
        }

        return outbox.stream().map(bv -> bv.asDocument().get("_id")).collect(Collectors.toList());
    }

    private BsonValue getDocumentKey(BsonDocument recordValueDocument) {
        return recordValueDocument.getDocument("documentKey").get("_id");
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
