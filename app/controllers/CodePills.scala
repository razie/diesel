package controllers

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject._
import play.api.mvc.Result
import razie.audit.Audit
import razie.diesel.engine.DieselAppContext.getActorSystem
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/** simple code pills controller
  *
  * a pill is a quick code lambda responding to a url
  *
  * pills are called with /pill/name
  */
@Singleton
class CodePillsCtl @Inject() (system:ActorSystem) extends RazController {

  sealed abstract class BasePill(val name: String) {
    def run (request:RazRequest) : Result
  }

  /** simple pill wrapper */
  private class Pill1(override val name: String, body: RazRequest => Result) extends BasePill(name) {
    override def run(stok:RazRequest) : Result = body(stok)
  }

  /** text pill wrapper */
  private class Pill2(override val name: String, body: RazRequest => String) extends BasePill(name) {
    override def run(stok:RazRequest) : Result = Ok(body(stok)).as("application/text")
  }

  val map = new AsyncMap[BasePill](system)
  implicit val timeout: Timeout = Timeout(5.seconds)

  type T = BasePill


  /** add a pill by name with the given body */
  def add(name: String)(body: RazRequest => Result): Unit = {
    map.put (name, new Pill1(name, body))
  }

  /** add a text pill by name with the given body */
  def addString(name: String)(body: RazRequest => String): Unit = {
    map.put (name, new Pill2(name, body))
  }

  // routes pill/:name?attrs
  def run(pill: String) = RAction.async { implicit request =>
    map.get(pill).map {op=>
      op.map {
        p=> try {
          p.run(request)
        } catch {
          case e : Throwable => {
            Audit.logdb("ERR_CODE_PILLS", "pill: " + pill, e)
            throw new IllegalStateException(e)
          }
        }
      } getOrElse {
        Unauthorized("pill not found")
      }
    }
  }

  // initialize the thing
  def init(): Unit = {
    add ("list") {request=>
      Ok("codePills: \n" + Await.result(map.list, timeout.duration));
    }
  }

  init()
}

/** async map backed by an actor AsyncMapActor...
  *
  * todo why not just use a simple TrieMap ?
  */
class AsyncMap[T] (system:ActorSystem) {

  private val p = Props(new AsyncMapActor[T]())
  private var map : Option[ActorRef] = Some(system.actorOf(p, "codePillsMap"))

  implicit val timeout: Timeout = Timeout(5.seconds)

  def put (k:String, v:T): Unit = {
    map.get ! (k -> v)
  }

  def get (k:String): Future[Option[T]] = {
    (map.get ? k).asInstanceOf[Future[Option[T]]]
  }

  def list : Future[String] = {
    (map.get ? "codePillsActor::list").asInstanceOf[Future[String]]
  }
}


/** a generic map actor, just have an async map
  *
  * put by sending a tuple and get by sending a key
  *
  * when getting use the !? and get the response as an Option
  */
class AsyncMapActor[T] extends Actor {
  private val myMap = new mutable.HashMap[String, T]()

  private def put (k:String, v:T): Unit = {
    self ! (k -> v)
  }

  private def get (k:String): Future[Option[T]] = {
    (self ? k)(Timeout(5.seconds)).asInstanceOf[Future[Option[T]]]
  }

  private def list : Future[String] = {
    (self ? "codePillsActor::list")(Timeout(5.seconds)).asInstanceOf[Future[String]]
  }


  override def receive = {
    case p:(String, T) => {
      myMap += (p._1 -> p._2)
    }

    case "codePillsActor::list" => {
      sender() ! myMap.keySet.mkString("\n")
    }

    case n:String => {
       sender() ! myMap.get(n)
      }
    }
}

