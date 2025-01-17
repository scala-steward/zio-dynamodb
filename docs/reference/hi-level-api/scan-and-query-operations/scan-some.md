---
id: scan-some
title: "scanSome"
---

```scala
  def scanSome[A: Schema](
    tableName: String,
    limit: Int
  ): DynamoDBQuery[A, (Chunk[A], LastEvaluatedKey)] = ???  
```

The `scanSome` operation is used page `limit` number of items in a table, and returns them in a tuple of `Chunk[A]` and `LastEvaluatedKey`. The `LastEvaluatedKey` can be used to continue scanning the table from where the last page left off using the `startKey` combinator. 

This paging behavior is useful when you have a large number of items in a table and you want to process them in smaller chunks to avoid memory issues - eg a REST API for a paging front end.

```scala
for {
  _                          <- put(tableName, Equipment("1", 2020, "Widget1", 1.0)).execute
  _                          <- put(tableName, Equipment("1", 2021, "Widget1", 2.0)).execute
  _                          <- put(tableName, Equipment("1", 2022, "Widget1", 2.1)).execute
  t                          <- scanSome[Equipment](tableName, limit = 2).execute
  (page1, lastEvaluatedKey1) = t 
  t2                         <- scanSome[Equipment](tableName, limit = 1).startKey(lastEvaluatedKey1).execute
} yield ()
```

## Combinators

```scala
<SCAN_SOME_QUERY>
  .consistency(<ConsistencyMode>)
  .startKey(<LastEvaluatedKey>)
  .filter(<ConditionExpression>) // eg Equipment.price > 1.0 - filtering is done server side AFTER the scan 
  .indexName(<IndexName>)        // use a secondary index    
```