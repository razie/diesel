package omp

import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.mvc.Action
import play.api.mvc.Request
import play.api.mvc.AnyContent
import play.api.mvc.Result

object Omp {

  import com.mongodb.casbah.Imports._
  import com.novus.salat.annotations._
  import com.novus.salat._
//  import db.RazSalatContext._
  import com.mongodb.util.JSON

  def json[T <: AnyRef](x: T)(implicit m: Manifest[T]) =
//    grater[T].asDBObject(x).mkString("<br>")
  x.toString
    .replaceAll("\\[", "[<ul>")
    .replaceAll("\\]", "</ul>]")
    .replaceAll("\\{", "{<ul>")
    .replaceAll("\\}", "</ul>}")
    .replaceAll("\\(", "(<ul>")
    .replaceAll("\\)", "</ul>)")
    .replaceAll("\\,", ",<li>")
  
  // db of stuff
  val dbmap = new scala.collection.mutable.HashMap[String, OrderContext]()

  val context = new OrderContext

  def kickit(id: String, args: Map[String, String]) {
    context.bssOrder = RazBss.mkOrder(id, args)
    RazBss send context.bssOrder
    RazOm execute context
  }

  kickit("1", Map("name" -> "John Appleseed"))
}

//-------------------------------------

class OEntity(val ttype: String, val id: String, val args: Map[String, String]) {
}

case class Item[T](val action: String, val entity: T, val args: Map[String, String] = Map()) {
  def +(item: Item[T]) = List(this, item)
  def ++(items: List[Item[T]]) = List(this) :: items
}

case class Order[T](
  override val id: String,
  override val args: Map[String, String],
  var items: List[Item[T]] = List())
  extends OEntity("Order", id, args) {
}

class OPEntity(ttype: String, id: String, args: Map[String, String] = Map()) extends OEntity(ttype, id, args)
class OREntity(ttype: String, id: String, args: Map[String, String] = Map()) extends OEntity(ttype, id, args)
case class Product(override val id: String, override val args: Map[String, String] = Map()) extends OPEntity("Product", id, args)
case class CFS(override val id: String, override val args: Map[String, String] = Map()) extends OPEntity("CFS", id, args)
case class RFS(override val id: String, override val args: Map[String, String] = Map()) extends OREntity("RFS", id, args)
case class Resource(override val id: String, override val args: Map[String, String] = Map()) extends OREntity("Resource", id, args)
case class Flow(val id: String, val items: List[Item[OREntity]], val flows: List[Flow] = List())

//-------------------------------------

case class OrderContext (
  var bssOrder: Order[OPEntity] = null,
  var incomingOrder: Order[OPEntity] = null,
  var fulfilmentOrder: Order[OREntity] = null,
  var soi: Flow = null,
  var executionPlan: Flow = null,
  var results : List[String] = List()
)

//--------------- COMPONENTS ----------------------

trait Bss {
  def mkOrder(id: String, args: Map[String, String]): Order[OPEntity]

  def send(o: Order[OPEntity])
}

trait Assesment {
  def accept(order: Order[OPEntity]) : String // from bss
  def validate(order: Order[OPEntity], ctx:OrderContext) : Boolean // from bss
  def decompose(order: Order[OPEntity], ctx: OrderContext) : Order[OREntity]
  def buildPlan(ctx: OrderContext) : Flow
}

trait Orchestration {
  def execute(plan:Flow, ctx:OrderContext) // track execution, notifications
}

trait Soi {
  def decompose(ctx: OrderContext): Flow
  def execute(item: Item[OREntity]): String 
}


//---------------SAMPLE DEMO----------------------

object RazBss extends Bss {

  def mkOrder(id: String, args: Map[String, String]): Order[OPEntity] = {
    val o = new Order[OPEntity](s"Bss.$id", args,
      Item[OPEntity]("add", new Product("Hsd", Map("plan"->"xgold"))) ::
        Item[OPEntity]("add", new Product("Voice")) :: Nil)
    o
  }

  def send(o: Order[OPEntity]) {
    RazOm accept o
  }
}

object RazOm {

  import Omp.context

  def validate(order: Order[OPEntity], ctx:OrderContext) : Boolean = {
    true
  }
  
  def decompose(order: Order[OPEntity], ctx: OrderContext): Order[OREntity] = {
    val fo = new Order[OREntity](s"FO.${order.id}", order.args,
        order.items.flatMap(
            psr.decompose(_).asInstanceOf[List[Item[OREntity]]]))
    fo
  }

  def accept(order: Order[OPEntity]) : String = {
    context.incomingOrder = order

    context.fulfilmentOrder = decompose(order, context)

    context.soi = RazSoi.decompose(context)

    context.executionPlan = context.soi
    
    context.incomingOrder.id
  }

  def execute(ctx:OrderContext) {
    ctx.results ++= ctx.executionPlan.items.map(RazSoi execute _)
  }

}

object RazSoi extends Soi {
  def decompose(ctx: OrderContext): Flow = {
    val id = ctx.fulfilmentOrder.id
    val wf = new Flow("WF." + id,
        ctx.fulfilmentOrder.items.flatMap(
            psr.decompose(_).asInstanceOf[List[Item[OREntity]]]))
    wf
  }

  def execute(item: Item[OREntity]): String = {
    s"success for ${item.toString}"
  }
}

object psr {

  def decompose[T](item: Item[T]) : List[Item[_]] = {
    item match {
          
      case Item(act, Product("Hsd", pargs), args) if pargs.get("plan").exists(_ == "gold") =>
        Item[RFS](act, RFS("Email", Map("quota" -> "100"))) +
          Item[RFS](act, RFS("Webspace", pargs))
          
      case Item(act, Product("Hsd", pargs), args) =>
        Item[RFS](act, RFS("Email", Map("quota" -> "10"))) +
          Item[RFS](act, RFS("Webspace", pargs))
          
      case Item(act, Product("Voice", pargs), args) =>
        Item[RFS](act, RFS("Line", pargs)) +
          Item[RFS](act, RFS("Feature", pargs))
          
      case Item(act, RFS("Email", pargs), args) =>
        Item[RFS](act, RFS("SmpEmail", pargs)) :: Nil
          
      case Item(act, RFS("Webspace", pargs), args) =>
        Item[RFS](act, RFS("SmpWebspace", pargs)) :: Nil
          
      case _ => List()
    }
  }

  // --------- maybe config in future?

  val yeah = { x: Map[String, String] => true }
  case class K(t: String, k: String, filter: Map[String, String] => Boolean = yeah)
  case class L(items: List[V])
  case class V(t: String, k: String, m: Map[String, String] => Map[String, String]) {
    def +(v: V) = List(this, v)
    def +(v: List[V]) = List(this) :: v
  }

  //  val decomp = Map(
  //    K("Product", "Hsd") -> L(
  //      V("RFS", "Email", identity) +
  //        V("RFS", "Webspace", identity)),
  //    K("Product", "Voice") -> L(
  //      V("RFS", "Line", identity) +
  //        V("RFS", "Feature", identity)),
  //    K("RFS", "Email") -> L(
  //      V("Task", "Email", identity) +
  //        V("RFS", "Webspace", identity)))

}

