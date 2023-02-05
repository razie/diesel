/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.wiki

import akka.actor.ActorSystem
import com.google.inject.{Guice, Inject, Singleton}
import play.api.cache.SyncCacheApi
import play.libs.Akka
import razie.db.RazMongo
import razie.wiki.model._
import razie.wiki.util.{AuthService, NoAuthService}

/** central point of customization - aka service registry / avoid the DI cascading mojo-jojo approach
  *
  * right now this is setup in Module, upon startup
  */
object Services {

  // the one instance, with injected components
  var instance : Services = null

  def auth: AuthService[WikiUser] = instance.auth
  def config: WikiConfig = instance.config
  def wikiAuth: WikiAuthorization = instance.wikiAuth
  def system: ActorSystem = instance.system

  def cache = instance.cache

  // this is only used for signed scripts - unsafe scripts are not ran here
  def runScriptImpl = instance.runScriptImpl

  def mkReactor = instance.mkReactor

  /** run the given script in the context of the given page and user as well as the query map */
  def runScript (s: String, lang:String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], typed: Map[String, Any], devMode:Boolean=false): String =
    instance.runScript(s, lang, page, user, query, typed)

  def noInitSample(): Unit = instance.noInitSample()

  /** is this website trusted? if not links will have a "exit" warning */
  def isSiteTrusted : (String,String) => Boolean = instance.isSiteTrusted

  /** initialize the event processor */
  def initCqrs (al:EventProcessor): Unit = instance.initCqrs(al)

  /** CQRS dispatcher */
  def ! (a: Any): Unit = instance ! a
}


/**
  * this is created in Module and with injected services
  */
@Singleton
class Services @Inject() (
  var auth: AuthService[WikiUser] = NoAuthService,
  var config: WikiConfig = new SampleConfig,
  var wikiAuth: WikiAuthorization = new NoWikiAuthorization,
  var system: ActorSystem = null,
  var cache: play.api.cache.SyncCacheApi = null
  ) {

  Services.instance = this

  // this is only used for signed scripts - unsafe scripts are not ran here
  var runScriptImpl : (String, String, Option[WikiEntry], Option[WikiUser], Map[String, String], Map[String, Any], Boolean) => String =
    (script: String, lang:String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], typed: Map[String, Any], devMode:Boolean) =>
      "TODO customize scripster"

  var mkReactor : (String, List[Reactor], Option[WikiEntry]) => Reactor = { (realm, fallBack, we)=>
//    new ReactorImpl(realm, fallBack, we)
     throw new IllegalArgumentException("Services.mkReactor implementation needed")
  }

  /** run the given script in the context of the given page and user as well as the query map */
  def runScript (s: String, lang:String, page: Option[WikiEntry], user: Option[WikiUser], query: Map[String, String], typed: Map[String, Any], devMode:Boolean=false): String =
    runScriptImpl(s,lang, page,user,query, typed, devMode)

  def noInitSample(): Unit = {
    /** connect to your database, with your connection properties, clustered or not etc */
    import com.mongodb.casbah.MongoConnection
    RazMongo.setInstance {
      MongoConnection("") apply ("")
    }
  }

  /** is this website trusted? if not links will have a "exit" warning */
  var isSiteTrusted : (String,String) => Boolean = {(r,s)=>false }


  /** initialize the event processor */
  def initCqrs (al:EventProcessor): Unit = BasicServices.initCqrs(al)

  /** CQRS dispatcher */
  def ! (a: Any): Unit = BasicServices ! a
}

