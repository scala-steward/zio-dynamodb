---
id: field-traversal
title: "Field Traversal"
---

We will be using the below model for the examples which contains a complex collection field `hobbies` and a nested case 
class field `address`. Note that ZIO DynamoDB also supports case classes as collection elements (not shown in this example).

```scala
final case class Address(number: String, street: String)
object Address {
  implicit lazy val schema: Schema.CaseClass2[String, String, Address] = DeriveSchema.gen[Address]

  // (1) number and street are ProjectionExpressions
  val (number, street) = ProjectionExpression.accessors[Address]
}
final case class Person(email: String, hobbies: Map[String, List[String]], address: Address)
object Person {
  implicit lazy val schema: Schema.CaseClass3[String, Map[String, List[String]], Address, Person] = DeriveSchema.gen[Person]

  // (2) email, hobbies and address are ProjectionExpressions
  val (email, hobbies, address) = ProjectionExpression.accessors[Person]
}
```

## `ProjectionExpression` field traversal combinators

DynamoDB allows us to dig into nested structures when updating or querying data using path expressions. Using the High Level API we do this in a _type safe_ way by
using the `ProjectionExpression`'s generated by the `accessors` method (see comments `1` and `2` above) as springboard 
for a bunch of traversal combinators. Note the `accessors` method in turn uses the reified optics feature of ZIO Schema.


Traversal Combinator | Description
---------------------|------------
`>>>`                | Returns a `ProjectionExpression` that traverses into a **product** (case class) or a **sum** type (sealed trait concrete instance)
`valueAt(key)`       | Returns a `ProjectionExpression` that traverses into a map field using a string key
`elementAt(N)`       | Returns a `ProjectionExpression` that traverses into a list field using a zero based index


```scala
  val person                                                             =
    Person("john@gmail.com", Map("sports" -> List("cricket", "football")), Address("1", "Main St"))

  // ProjectionExpressions extracted deliberately to illustrate the types
  val addressToStreetPE: ProjectionExpression[Person, String]            =
    (Person.address >>> Address.street) 
  val valueAtHobbiesPE: ProjectionExpression[Person, List[String]]       =
    Person.hobbies.valueAt("sports")
  val valueAtAndElementAtHobbiesPE: ProjectionExpression[Person, String] =
    Person.hobbies.valueAt("sports").elementAt(0)

  for {
    _ <- DynamoDBQuery.put("people", person).execute

    _ <- DynamoDBQuery
           .update("people")(Person.email.partitionKey === "john@gmail.com")(
             addressToStreetPE.set("2nd St")
           )
           .execute

    _ <- DynamoDBQuery
           .update("people")(Person.email.partitionKey === "john@gmail.com")(
             valueAtHobbiesPE.set(List("tennis", "rugby"))
           )
           .execute

    _ <- DynamoDBQuery
           .update("people")(Person.email.partitionKey === "john@gmail.com")(
             valueAtAndElementAtHobbiesPE.set("cricket")
           )
           .execute

  } yield ()
```

## Product traversal

From the the example above: 
```scala
  val addressToStreetPE: ProjectionExpression[Person, String]            =
    (Person.address >>> Address.street) 
```
`addressToStreetPE` is an example of traversing into a product (case class).

## Sum type traversal

To illustrate sum type traversal, consider the below model with sum type (sealed trait) `BilledBody`:

```scala
  @discriminatorName("billedType")
  sealed trait BilledBody

  object BilledBody {
    final case class BilledMonthly(month: Int) extends BilledBody
    object BilledMonthly {
      implicit lazy val schema: Schema.CaseClass1[Int, BilledMonthly] = DeriveSchema.gen[BilledMonthly]
      val month: ProjectionExpression[BilledMonthly, Int]             = ProjectionExpression.accessors[BilledMonthly]
    }
    final case class BilledYearly(year: Int) extends BilledBody
    object BilledYearly  {
      implicit lazy val schema: Schema.CaseClass1[Int, BilledYearly] = DeriveSchema.gen[BilledYearly]
      val year: ProjectionExpression[BilledYearly, Int]              = ProjectionExpression.accessors[BilledYearly]
    }
    implicit val schema: Schema.Enum2[BilledMonthly, BilledYearly, BilledBody] = DeriveSchema.gen[BilledBody]
    val (monthly, yearly) = ProjectionExpression.accessors[BilledBody]
  }

  final case class Invoice(id: Int, body: BilledBody)
  object Invoice {
    implicit lazy val schema: Schema.CaseClass2[Int, BilledBody, Invoice] = DeriveSchema.gen[Invoice]
    val (id, body)                                                       = ProjectionExpression.accessors[Invoice]
  }
```

We can access the `BilledYearly` case class field `year` using the `>>>` combinator as shown below:

```scala
  val invoiceToBilledYearlyToYearPE: ProjectionExpression[Invoice, Int] =
    Invoice.body >>> BilledBody.yearly >>> BilledYearly.year

  val yearlyInvoice = Invoice(1, BilledYearly(2021))

  for {
    _ <- DynamoDBQuery.put("invoices", yearlyInvoice).execute
    _ <- DynamoDBQuery
           .update("invoices")(Invoice.int.partitionKey === 1)(
             invoiceToBilledYearlyToYearPE.set(2022)
           )
           .execute

  } yield ()
```
