# KAFKA MONGODB OUTBOX REMOVER

This application is a Kafka Consumer which must consume topics that represent raw data from a MongoDB Change Streams.

Outbox Remover is part of a pipeline which aims to implement the [Outbox Transactional Pattern](https://microservices.io/patterns/data/transactional-outbox.html) using MongoDB and Kafka.

Once the main application data is pulled from MongoDB to Kafka, including events to be consumed by other applications, the function of Outbox Remover is to remove the events present in MongoDB Document. Thus, this array of events is always kept small in MongoDB Document while the information is already in Kafka for further processing in the pipeline.

The Kafka record value must have the following minimum schema:

```json
{
  "fullDocument": {
    "outbox": [
        {"_id": {}}
    ]
  },
  "ns": {
    "db": "databaseName",
    "coll": "collectionName"
  },
  "documentKey": {
    "_id": {}
  }
}
```

Virtually all of the above schema is already the standard format produced by [MongoDB Kafka Connector](https://docs.mongodb.com/kafka-connector/current/). The only field that is specific to the Outbox Remover is the "outbox" field. The "outbox" field must be an array of objects representing the events that the main entity (represented by the fullDocument) wants to send to its clients via Kafka.

When processing a record value with the above scheme, what the Outbox Remover does is to remove the elements present in the outbox field in the corresponding database/collection.