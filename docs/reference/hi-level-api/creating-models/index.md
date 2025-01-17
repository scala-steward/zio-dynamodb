---
id: index
title: "Creating Models"
sidebar_label: "Creating Models"
---

The High Level API provides automatic serialization and deserialization of Scala case classes to and from DynamoDB types.
This is done by requiring that an implicit ZIO Schema instance is in scope for the case class. This schema instance is 
generated semi-automatically by using the ZIO Schema `DeriveSchema.gen[A]` - placing this in the companion object of 
the case class ensures that this implicit is automatically in scope.

```scala
final case class Person(email: String, hobbies: Map[String, List[String]], registrationDate: Instant)
object Person {
  implicit val schema: Schema.CaseClass3[String, Map[String, List[String]], Instant, Person] =
    DeriveSchema.gen[Person]
}
```

This semi-automatically derived schema is used to automatically generate codecs for the case class (in the `Codecs` object)
to perform the serialization and deserialization to and from DynamoDB types.

All standard Scala types are supported by the codecs, as well as nested case classes and collections. Note that where 
possible Scala types are mapped to corresponding DynamoDB types, for example Scala `Map`'s and `Set`'s are mapped to 
native DynamoDB types.

Scala Scalar Types | Native DynamoDB Type | Notes
------------|----------------------|--------------
`Unit`              | `NULL`                    
`String`            | `S`                    
Numeric Types       | `N`                    
`Collection[byte]`  | `B`                    | Any Scala collection type of byte is serialized to a DynamoDB binary type 
`Boolean`           | `BOOL`                 
`java.time.*`       | `S`                    | There is no native date/time support. Instant is serialized to a string in ISO-8601 format
`java.util.UUID`    | `S`                    | There is no native `UUID` support
`java.util.Currency`| `S`                   | There is no native `Currency` support

Note in the below table that types `A`, `K` and `V` can be collections or case classes as well as scalar types.

Scala Collection Types | Native DynamoDB Type | Notes
------------|----------------------|--------------
`Option[A]`           |                      | `Some`/`None` are represented by the presence and the absence of the field in the DynamoDB item resepctively.
`List[A]`             | `L`                    |
`Set[String]`         | `SS`                   |
`Set` of numeric type | `NS`                   |
`Set` of binary type  | `BS`                   |
`Set[A]` of other type| `L`                    | If type is not a string or a numeric then a list is used
`Map[String, A]`      | `M`                    | if key type is a string then a native Map is used
`Map[K, V]`           | `L`                    | otherwise a list of tuple of key value pair is used

Note during model development you can use the optional `zio-dynamodb-json` module to view the DynamoDB types generated by the codecs.
eg for the above example:

```scala
import zio.dynamodb.json._
val person = Person("email", Map("sports" -> List("cricket", "football")), Instant.now)
println(person.toJsonStringPretty[Person])
```
...would print the following which is representation of the native DynamoDB types and data in a standard JSON format used in AWS console views:
```json
{
  "registrationDate" : {
    "S" : "2024-12-05T05:47:46.300286Z"
  },
  "hobbies" : {
    "sports" : {
      "L" : [
        {
          "S" : "cricket"
        },
        {
          "S" : "football"
        }
      ]
    }
  },
  "email" : {
    "S" : "email"
  }
}
```

For more information about using the `zio-dynamodb-json` module please see the [ZIO DynamoDB Json](reference/zio-dynamodb-json.md) reference page.

For more information about customising data mappings please see the [Codec Customization](guides/codec-customization.md) guide.







