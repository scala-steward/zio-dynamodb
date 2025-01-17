package zio.dynamodb

import zio.dynamodb.DynamoDBQuery.BatchGetItem.TableGet
import zio.dynamodb.DynamoDBQuery._
import zio.dynamodb.TestDynamoDBExecutor.PkAndItem
import zio.stm.{ STM, TMap, ZSTM }
import zio.stream.{ Stream, ZStream }
import zio.{ Chunk, IO, Ref, UIO, ZIO }

private[dynamodb] final case class TestDynamoDBExecutorImpl private[dynamodb] (
  queries: Ref[List[DynamoDBQuery[_, _]]],
  tableMap: TMap[TableName, TMap[PrimaryKey, Item]],
  tablePkNameMap: TMap[TableName, String]
) extends DynamoDBExecutor
    with TestDynamoDBExecutor {
  self =>

  override def execute[A](query: DynamoDBQuery[_, A]): ZIO[Any, DynamoDBError, A] = {
    val result: ZIO[Any, DynamoDBError, A] = query match {
      case BatchGetItem(requestItemsMap, _, _, _)                                 =>
        val requestItems: Seq[(TableName, TableGet)] = requestItemsMap.toList

        val foundItems: IO[DynamoDBError, Seq[(TableName, Option[Item])]] =
          ZIO
            .foreach(requestItems) {
              case (tableName, tableGet) =>
                ZIO.foreach(tableGet.keysSet) { key =>
                  fakeGetItem(tableName, key).map((tableName, _))
                }
            }
            .map(_.flatten)

        val response: IO[DynamoDBError, BatchGetItem.Response] = for {
          pairs       <- foundItems
          pairFiltered = pairs.collect { case (tableName, Some(item)) => (tableName, item) }
          mapOfSet     = pairFiltered.foldLeft[MapOfSet[TableName, Item]](MapOfSet.empty) {
                           case (acc, (tableName, listOfItems)) => acc + (tableName -> listOfItems)
                         }
        } yield BatchGetItem.Response(mapOfSet)

        response

      case BatchWriteItem(requestItems, _, _, _, _)                               =>
        val results: ZIO[Any, DynamoDBError, Unit] = ZIO.foreachDiscard(requestItems.toList) {
          case (tableName, setOfWrite) =>
            ZIO.foreachDiscard(setOfWrite) { write =>
              write match {
                case BatchWriteItem.Put(item)  =>
                  fakePut(tableName, item)
                case BatchWriteItem.Delete(pk) =>
                  fakeDelete(tableName, pk)
              }
            }

        }
        results.map(_ => BatchWriteItem.Response(None))

      case GetItem(tableName, key, _, _, _, _)                                    =>
        fakeGetItem(tableName, key)

      case PutItem(tableName, item, _, _, _, _, _)                                =>
        fakePut(tableName, item)

      // TODO Note UpdateItem is not currently supported as it uses an UpdateExpression

      case DeleteItem(tableName, key, _, _, _, _, _)                              =>
        fakeDelete(tableName, key)

      case ScanSome(tableName, limit, _, _, exclusiveStartKey, _, _, _, _)        =>
        fakeScanSome(tableName, exclusiveStartKey, Some(limit))

      case ScanAll(tableName, _, maybeLimit, _, _, _, _, _, _, _)                 =>
        fakeScanAll(tableName, maybeLimit)

      case QuerySome(tableName, limit, _, _, exclusiveStartKey, _, _, _, _, _, _) =>
        fakeScanSome(tableName, exclusiveStartKey, Some(limit))

      case QueryAll(tableName, _, maybeLimit, _, _, _, _, _, _, _, _)             =>
        fakeScanAll(tableName, maybeLimit)

      // TODO: implement CreateTable

      case unknown                                                                =>
        ZIO.die(new Exception(s"Constructor $unknown not implemented yet"))
    }

    queries.update(_ :+ query) *> result
  }

  private def tableError(tableName: TableName): DynamoDBError =
    DynamoDBError.ItemError.ValueNotFound(s"table $tableName does not exist")

  private def tableMapAndPkName(tableName: TableName): ZSTM[Any, DynamoDBError, (TMap[PrimaryKey, Item], String)] =
    for {
      tableMap <- for {
                    maybeTableMap <- self.tableMap.get(tableName)
                    tableMap      <- maybeTableMap.fold[STM[DynamoDBError, TMap[PrimaryKey, Item]]](
                                       STM.fail[DynamoDBError](tableError(tableName))
                                     )(
                                       STM.succeed(_)
                                     )
                  } yield tableMap
      pkName   <- self.tablePkNameMap
                    .get(tableName)
                    .flatMap(_.fold[STM[DynamoDBError, String]](STM.fail(tableError(tableName)))(STM.succeed(_)))
    } yield (tableMap, pkName)

  private def pkForItem(item: Item, pkName: String): PrimaryKey                                  =
    Item(item.map.filter { case (key, _) => key == pkName })

  private def fakeGetItem(tableName: TableName, pk: PrimaryKey): IO[DynamoDBError, Option[Item]] =
    (for {
      (tableMap, _) <- tableMapAndPkName(tableName)
      maybeItem     <- tableMap.get(pk)
    } yield maybeItem).commit

  private def fakePut(tableName: TableName, item: Item): IO[DynamoDBError, Option[Item]] =
    (for {
      (tableMap, pkName) <- tableMapAndPkName(tableName)
      pk                  = pkForItem(item, pkName)
      _                  <- tableMap.put(pk, item)
    } yield None).commit

  private def fakeDelete(tableName: TableName, pk: PrimaryKey): IO[DynamoDBError, Option[Item]] =
    (for {
      (tableMap, _) <- tableMapAndPkName(tableName)
      _             <- tableMap.delete(pk)
    } yield None).commit

  private def fakeScanSome(
    tableName: TableName,
    exclusiveStartKey: LastEvaluatedKey,
    maybeLimit: Option[Int]
  ): IO[DynamoDBError, (Chunk[Item], LastEvaluatedKey)] =
    (for {
      (itemMap, pkName) <- tableMapAndPkName(tableName)
      xs                <- itemMap.toList
      result            <- STM.succeed(slice(sort(xs, pkName), exclusiveStartKey, maybeLimit))
    } yield result).commit

  private def fakeScanAll[R](tableName: TableName, maybeLimit: Option[Int]): UIO[Stream[DynamoDBError, Item]] = {
    val start: LastEvaluatedKey = None
    ZIO.succeed(
      ZStream
        .paginateZIO(start) { lek =>
          fakeScanSome(tableName, lek, maybeLimit).map {
            case (chunk, lek) =>
              lek match {
                case None => (chunk, None)
                case lek  => (chunk, Some(lek))
              }
          }
        }
        .flattenChunks
    )

  }

  private def sort(xs: Seq[PkAndItem], pkName: String): Seq[PkAndItem] =
    xs.toList.sortWith {
      case ((pkL, _), (pkR, _)) =>
        (pkL.map.get(pkName), pkR.map.get(pkName)) match {
          case (Some(left), Some(right)) => attributeValueOrdering(left, right)
          case _                         => false
        }
    }

  private def slice(
    xs: Seq[PkAndItem],
    exclusiveStartKey: LastEvaluatedKey,
    maybeLimit: Option[Int]
  ): (Chunk[Item], LastEvaluatedKey) =
    maybeNextIndex(xs, exclusiveStartKey).map { index =>
      val limit              = maybeLimit.getOrElse(xs.length)
      val slice              = xs.slice(index, index + limit)
      val chunk: Chunk[Item] = Chunk.fromIterable(slice.map { case (_, item) => item })
      val lek                =
        if (index + limit >= xs.length) None
        else {
          val (pk, _) = slice.last
          Some(pk)
        }
      (chunk, lek)
    }.getOrElse((Chunk.empty, None))

  private def maybeNextIndex(xs: Seq[PkAndItem], exclusiveStartKey: LastEvaluatedKey): Option[Int] =
    exclusiveStartKey.fold[Option[Int]](Some(0)) { pk =>
      val foundIndex      = xs.indexWhere { case (pk2, _) => pk2 == pk }
      val afterFoundIndex =
        if (foundIndex == -1) None
        else Some(math.min(foundIndex + 1, xs.length))
      afterFoundIndex
    }

  // TODO come up with a correct ordering scheme - for now orderings are only correct for scalar types
  private def attributeValueOrdering(left: AttributeValue, right: AttributeValue): Boolean =
    (left, right) match {
      case (AttributeValue.Binary(valueL), AttributeValue.Binary(valueR))       =>
        valueL.toString.compareTo(valueR.toString) < 0
      case (AttributeValue.Bool(valueL), AttributeValue.Bool(valueR))           =>
        valueL.compareTo(valueR) < 0
      case (AttributeValue.List(valueL), AttributeValue.List(valueR))           =>
        valueL.toString.compareTo(valueR.toString) < 0
      case (AttributeValue.Map(valueL), AttributeValue.Map(valueR))             =>
        valueL.toString.compareTo(valueR.toString) < 0
      case (AttributeValue.Number(valueL), AttributeValue.Number(valueR))       =>
        valueL.compareTo(valueR) < 0
      case (AttributeValue.Null, AttributeValue.Null)                           =>
        false
      case (AttributeValue.String(valueL), AttributeValue.String(valueR))       =>
        valueL.compareTo(valueR) < 0
      case (AttributeValue.StringSet(valueL), AttributeValue.StringSet(valueR)) =>
        valueL.toString.compareTo(valueR.toString) < 0
      case _                                                                    => false
    }

  override def addTable(tableName: String, pkFieldName: String, pkAndItems: PkAndItem*): UIO[Unit] =
    (for {
      _    <- tablePkNameMap.put(TableName(tableName), pkFieldName)
      tmap <- TMap.empty[PrimaryKey, Item]
      _    <- STM.foreach(pkAndItems) {
                case (pk, item) => tmap.put(pk, item)
              }
      _    <- tableMap.put(TableName(tableName), tmap)
    } yield ()).commit

  override def addItems(tableName: String, pkAndItems: (PrimaryKey, Item)*): ZIO[Any, DynamoDBError, Unit] =
    (for {
      (tableMap, _) <- tableMapAndPkName(TableName(tableName))
      _             <- STM.foreach(pkAndItems) {
                         case (pk, item) => tableMap.put(pk, item)
                       }
    } yield ()).commit

  override def recordedQueries: UIO[List[DynamoDBQuery[_, _]]] = self.queries.get
}
