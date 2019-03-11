package tests

import com.outr.giantscala._
import com.outr.giantscala.dsl.SortField
import com.outr.giantscala.failure.FailureType
import com.outr.giantscala.oplog.Delete
import io.circe.Printer
import org.mongodb.scala.bson.collection.immutable.Document
import org.scalatest.{Assertion, AsyncWordSpec, Matchers}
import reactify.Channel
import scribe.Logger
import scribe.format._
import scribe.modify.ClassNameFilter

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.language.implicitConversions

class DBCollectionSpec extends AsyncWordSpec with Matchers {
  "DBCollection" should {
    var inserts = ListBuffer.empty[Person]
    var deletes = ListBuffer.empty[Delete]

    "reconfigure logging" in {
      Logger.root.clearHandlers().withHandler(
        formatter = formatter"$date $levelPaddedRight $position - ${scribe.format.message}$newLine",
        modifiers = List(ClassNameFilter.startsWith("org.mongodb.driver.cluster", exclude = true))
      ).replace()
      Future.successful(succeed)
    }
    "drop the database so it's clean and ready" in {
      Database.drop().map(_ => true should be(true))
    }
    "initiate database upgrades" in {
      Database.init().map { _ =>
        succeed
      }
    }
    "verify the version" in {
      val version = Database.version.major
      version should be >= 3
    }
    "create successfully" in {
      Database.person shouldNot be(null)
    }
    "start monitoring people" in {
      Database.person.monitor.insert.attach { person =>
        inserts += person
      }
      Database.person.monitor.delete.attach { delete =>
        deletes += delete
      }
      noException should be thrownBy Database.person.monitor.start()
    }
    "validate a field value" in {
      val f = Field[String]("MyField")
      val value = f("test").pretty(Printer.noSpaces)
      value should be("""{"MyField":"test"}""")
    }
    "insert a person" in {
      Database.person.insert(Person(name = "John Doe", age = 30, _id = "john.doe")).map { result =>
        result.isRight should be(true)
        val p = result.right.get
        p.name should be("John Doe")
        p.age should be(30)
        p._id should be("john.doe")
      }
    }
    // TODO: uncomment when monitoring consistently works (only works sometimes)
//    "verify the insert was monitored" in {
//      waitFor(inserts.length should be(1)).map { _ =>
//        val p = inserts.head
//        p.name should be("John Doe")
//        p.age should be(30)
//        p._id should be("john.doe")
//      }
//    }
    "query one person back" in {
      Database.person.all().map { people =>
        people.length should be(1)
        val p = people.head
        p.name should be("John Doe")
        p.age should be(30)
        p._id should be("john.doe")
      }
    }
    "query back by name" in {
      Database.person.byName("John Doe").map { people =>
        people.length should be(1)
        val p = people.head
        p.name should be("John Doe")
        p.age should be(30)
        p._id should be("john.doe")
      }
    }
    "trigger constraint violation inserting the same name twice" in {
      Database.person.insert(Person(name = "John Doe", age = 31, _id = "john.doe2")).map { result =>
        result.isLeft should be(true)
        val failure = result.left.get
        failure.`type` should be(FailureType.DuplicateKey)
      }
    }
    "delete one person" in {
      Database.person.all().flatMap { people =>
        val p = people.head
        Database.person.delete(p._id).map { _ =>
          people.length should be(1)
        }
      }
    }
    "verify the delete was monitored" in {
      waitFor(deletes.length should be(1)).map { _ =>
        deletes.length should be(1)
      }
    }
    "do a batch insert" in {
      inserts.clear()
      Database.person.batch.insert(
        Person("Person A", 1, _id = "personA"),
        Person("Person B", 2, _id = "personB")
      ).execute().map { result =>
        result.getInsertedCount should be(2)
      }
    }
    "verify the batch insert was monitored" in {
      waitFor(inserts.length should be(2)).map { _ =>
        val p = inserts.head
        p.name should be("Person A")
        p.age should be(1)
        p._id should be("personA")
      }
    }
    "do a batch update" in {
      Database.person.batch.update(
        Person("Person A", 123, _id = "personA"),
        Person("Person B", 234, _id = "personB")
      ).execute().map { result =>
        result.getModifiedCount should be(2)
      }
    }
    "query two people back" in {
      Database.person.all().map { people =>
        people.length should be(2)
        val p = people.head
        p.name should be("Person A")
        p.age should be(123)
        p._id should be("personA")
      }
    }
    "query Person A back in a aggregate DSL query" in {
      import Database.person._
      aggregate
        .`match`(name === "Person A")
        .toFuture
        .map { people =>
          people.map(_.name) should be(List("Person A"))
        }
    }
    "query Person A back in an aggregate DSL query using toStream" in {
      import Database.person._
      var people = List.empty[Person]
      val channel = Channel[Person]
      channel.attach { person =>
        people = person :: people
      }
      aggregate
        .sort(SortField.Descending(name))
        .toStream(channel)
        .map { received =>
          received should be(2)
          people.map(_.name) should be(List("Person A", "Person B"))
        }
    }
    "aggregate count" in {
      import Database.person._
      aggregate.count().toFuture.map { results =>
        results should be(List(2))
      }
    }
    "aggregate sort by name ascending" in {
      import Database.person._
      aggregate
        .sort(SortField.Ascending(name))
        .toFuture
        .map { people =>
          val names = people.map(_.name)
          names should be(List("Person A", "Person B"))
        }
    }
    "aggregate sort by name descending" in {
      import Database.person._
      aggregate
        .sort(SortField.Descending(name))
        .toFuture
        .map { people =>
          val names = people.map(_.name)
          names should be(List("Person B", "Person A"))
        }
    }
    "aggregate skip" in {
      import Database.person._
      aggregate
        .sort(SortField.Ascending(name))
        .skip(1)
        .toFuture
        .map { people =>
          val names = people.map(_.name)
          names should be(List("Person B"))
        }
    }
    "aggregate limit" in {
      import Database.person._
      aggregate
        .sort(SortField.Ascending(name))
        .limit(1)
        .toFuture
        .map { people =>
          val names = people.map(_.name)
          names should be(List("Person A"))
        }
    }
    "query Person A back in a aggregate DSL query with conversion" in {
      import Database.person._
      aggregate
        .project(name.include, _id.exclude)
        .`match`(name === "Person A")
        .as[PersonName]
        .toFuture.map { people =>
          people.map(_.name) should be(List("Person A"))
        }
    }
    "verify $group with $addToSet" in {
      import Database.person._
      val query = aggregate.group(_id.set("names"), name.addToSet("names")).toQuery(includeSpaces = false)
      query should be("""db.person.aggregate([{"$group":{"_id":"names","names":{"$addToSet":"$name"}}}])""")
    }
    "verify $or" in {
      import Database.person._
      val query = aggregate.`match`(name === "Person A" || name === "Person B").toQuery(includeSpaces = false)
      query should be("""db.person.aggregate([{"$match":{"$or":[{"name":"Person A"},{"name":"Person B"}]}}])""")
    }
    "verify $and" in {
      import Database.person._
      val query = aggregate.`match`(name === "Person A" && name === "Person B").toQuery(includeSpaces = false)
      query should be("""db.person.aggregate([{"$match":{"$and":[{"name":"Person A"},{"name":"Person B"}]}}])""")
    }
    "verify $in" in {
      import Database.person._
      val query = aggregate.`match`(name.in("Person A", "Person B")).toQuery(includeSpaces = false)
      query should be("""db.person.aggregate([{"$match":{"name":{"$in":["Person A","Person B"]}}}])""")
    }
    "verify $size" in {
      import Database.person._
      val query = aggregate.`match`(name.size(10)).toQuery(includeSpaces = false)
      query should be("""db.person.aggregate([{"$match":{"name":{"$size":10}}}])""")
    }
    "verify aggregate $addFields" in {
      import Database.person._
      val query = aggregate.addFields(Field("person").arrayElemAt("$people", 0)).toQuery(includeSpaces = false)
      query should be("""db.person.aggregate([{"$addFields":{"person":{"$arrayElemAt":["$people",0]}}}])""")
    }
    "verify $objectToArray converts to proper query" in {
      import Database.person._
      val query = aggregate.project(name.objectToArray("names")).toQuery(includeSpaces = false)
      query should be("""db.person.aggregate([{"$project":{"name":{"$objectToArray":"$names"}}}])""")
    }
    "verify a complex query" in {
      import Database.person._
      val status = field[String]("status")
      val count = field[String]("count")
      val counts = field[String]("counts")
      val query = aggregate
        .project(status.objectToArray(status))
        .project(status.arrayElemAt(status.key, 0))
        .group(_id.set(status.op), sum("count"))
        .project(_id.exclude, status.set(name.set(_id.op), count.set(count.op)))
        .group(_id.set(counts), status.addToSet(counts))
        .toQuery(includeSpaces = false)
      query should be("""db.person.aggregate([{"$project":{"status":{"$objectToArray":"$status"}}},{"$project":{"status":{"$arrayElemAt":["$status.k",0]}}},{"$group":{"_id":"$status","count":{"$sum":1}}},{"$project":{"_id":0,"status":{"name":"$_id","count":"$count"}}},{"$group":{"_id":"counts","counts":{"$addToSet":"$status"}}}])""")
    }
    "stop the oplog" in {
      noException should be thrownBy Database.oplog.stop()
    }
  }

  def waitFor(condition: => Assertion,
              time: Long = 15000L,
              startTime: Long = System.currentTimeMillis()): Future[Assertion] = {
    try {
      val result: Assertion = condition
      Future.successful(result)
    } catch {
      case t: Throwable => if (System.currentTimeMillis() - startTime > time) {
        Future.failed(t)
      } else {
        Future {
          Thread.sleep(10L)
        }.flatMap { _ =>
          waitFor(condition, time, startTime)
        }
      }
    }
  }
}

case class Person(name: String,
                  age: Int,
                  created: Long = System.currentTimeMillis(),
                  modified: Long = System.currentTimeMillis(),
                  _id: String) extends ModelObject

case class PersonName(name: String)

class PersonCollection extends DBCollection[Person]("person", Database) {
  import scribe.Execution.global

  val name: Field[String] = Field("name")
  val age: Field[Int] = Field("age")
  val created: Field[Long] = Field("created")
  val modified: Field[Long] = Field("modified")
  val _id: Field[String] = Field("_id")

  override val converter: Converter[Person] = Converter.auto[Person]

  override def indexes: List[Index] = List(
    name.index.ascending.unique
  )

  def byName(name: String): Future[List[Person]] = {
   aggregate.`match`(this.name === name).toFuture
  }
}

object Database extends MongoDatabase(name = "giant-scala-test", maxWaitQueueSize = 100) {
  val person: PersonCollection = new PersonCollection
}