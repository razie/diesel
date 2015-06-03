

package wiki

import com.mongodb.casbah.MongoConnection
import play.api.mvc.WithFilters
import razie.db.RazMongo
import razie.wiki.model.{Reactor, WikiInst}
import razie.wiki.parser.WikiParserT
import razie.wiki.{SampleConfig, Services, WikiConfig}

/** customize some global handling errors */
object TestInit extends WithFilters {

  System.setProperty("rk.properties", "/Users/raz/w/racerkidz/rk.properties")
  Services.config = SampleConfig

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
class MyReactor(realm: String) extends Reactor(realm, None, None) {

  /** my wiki - used to compose my own parser */
  class MyWikiInst(realm: String) extends WikiInst(realm, None) {
    class MyWikiParserCls(val realm: String) extends WikiParserT {
    }

    override def mkParser = new MyWikiParserCls(realm)
  }

  override val wiki: WikiInst = new MyWikiInst(realm)
}

