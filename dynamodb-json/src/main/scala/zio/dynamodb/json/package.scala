package zio.dynamodb

import zio.dynamodb.DynamoDBError.ItemError
import zio.dynamodb.json.DynamodbJsonCodec.Encoder
import zio.schema.Schema

package object json {

  implicit class AttrMapJsonOps(am: AttrMap) {
    def toJsonString: String       = Encoder.attributeValueToJsonString(am.toAttributeValue)
    def toJsonStringPretty: String = Encoder.attributeValueToJsonStringPretty(am.toAttributeValue)
  }

  implicit class ProductJsonOps[A](a: A) {
    def toJsonString[A2 >: A](implicit ev: Schema[A2]): String       =
      Encoder.attributeValueToJsonString(toItem[A2](a).toAttributeValue)
    def toJsonStringPretty[A2 >: A](implicit ev: Schema[A2]): String =
      Encoder.attributeValueToJsonStringPretty(toItem[A2](a).toAttributeValue)
  }

  def parseItem(json: String): Either[DynamoDBError.ItemError, AttrMap] =
    DynamodbJsonCodec.Decoder
      .jsonStringToAttributeValue(json)
      .left
      .map(DynamoDBError.ItemError.DecodingError.apply)
      .flatMap(FromAttributeValue.attrMapFromAttributeValue.fromAttributeValue)

  def parse[A: Schema](jsonString: String): Either[DynamoDBError.ItemError, A] =
    parseItem(jsonString).flatMap(fromItem[A])

  private def toItem[A: Schema](a: A): Item =
    FromAttributeValue.attrMapFromAttributeValue
      .fromAttributeValue(AttributeValue.encode(a))
      .getOrElse(throw new Exception(s"error encoding $a"))

  private def fromItem[A: Schema](item: Item): Either[ItemError, A] = {
    val av = ToAttributeValue.attrMapToAttributeValue.toAttributeValue(item)
    av.decode(Schema[A])
  }

}
