

package wiki

import com.mongodb.DBObject
import com.mongodb.casbah.{Imports, MongoConnection}
import play.api.mvc.WithFilters
import razie.db.RazMongo
import razie.wiki.model._
import razie.wiki.parser.WikiParserT
import razie.wiki.{SampleConfig, Services, WikiConfig}

/** customize some global handling errors */
object TestInit extends WithFilters {

  System.setProperty("rk.properties", "/Users/raz/w/racerkidz/rk.properties")
  Services.config = new SampleConfig

  def init = {
    Services.config = new WikiConfig {
      override def reloadUrlMap = {}
    }

    /************** MONGO INIT *************/
    RazMongo.setInstance {
      lazy val conn = MongoConnection("localhost")

      /** the actual database - done this way to run upgrades before other code uses it */
      com.mongodb.casbah.commons.conversions.scala.RegisterConversionHelpers()
      com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers()

      // authenticate
      val c = Services.config
      val db = conn(c.mongodb)
      if (!db.authenticate(c.mongouser, c.mongopass)) {
        throw new Exception("Cannot authenticate. Login failed.")
      }
      db
    }

    // OPTIONAL - customize the reactor/wiki/parser
    Services.mkReactor = { (realm, fallBack, we) =>
      new MyReactor(realm)
    }

//     create the default page
//    if (!Wikis.find(WID.fromPath("Admin:Sample1").get).isDefined) {
//      razie.db.tx { implicit txn =>
//        new WikiEntry("Admin", "Sample1", "Sample1", "md", """
//Congratulations - you made it work!
//
//This is a sample first page. Try to create a new page: [[Admin:Sample1-1]].
//                                                           """, new ObjectId()).create
//      }
//
//    }

  }
}

/** OPTIONAL: my own reactor - customize the customizables */
class MyReactor(override val realm: String) extends ReactorImpl(realm, Nil, None) {

  override val wiki: WikiInst = new MyWikiInst(realm)

  /** my wiki - used to compose my own parser */
  class MyWikiInst(override val realm: String) extends WikiInstImpl(realm, Nil, (x=> null)) {

    class MyWikiParserCls(val realm: String) extends WikiParserT {
    }

    override def mkParser = new MyWikiParserCls(realm)

  }

  override def domain = ???
}

