package mod.cart

import model.Users
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.audit.Audit
import razie.db.{REntity, RMany, ROne, Txn}
import razie.wiki.model.UWID

/**
  * Created by raz on 2016-12-21.
  *
  * todo save the discount in the cart, as a record, on checkout
  */
case class Cart (
  userId  : ObjectId,     // per user
  clubWid : UWID,         // per club or whatever - NEEDed for paypal credentials from settings
  items   : Seq[CartItem] = Seq.empty,
  props : Map[String,String] = Map.empty,
  state : String = Cart.STATE_CREATED,
  archived : Boolean = false,
  cartRedirect : Option[String] = None, // redirect at end of payment.
  discount : Option[Int] = None, // discount perc, if any
  crDtm   : DateTime = DateTime.now,
  updDtm  : DateTime = DateTime.now,
  _id     : ObjectId = new ObjectId()
  ) extends REntity[Cart] {

  def isOpen = state startsWith "open."

  def add (item:CartItem)(implicit txn:Txn): Unit = {
    Audit.logdb("CART", "ADD_ITEM", Users.findUserById(userId).map(u=> s"${u.ename} email=${u.emailDec}").mkString, s"cartid=${_id.toString}", item)
    val c = copy(items = items ++ Seq(item), updDtm = DateTime.now)
    c.update
  }

  def rm (itemQuery:String)(implicit txn:Txn): Unit = {
    val c = copy(items = items.filter(_.entQuery != itemQuery), updDtm = DateTime.now)
    c.update
  }

  def currency = {
    val c = items.collectFirst {
      case i if i.price.oneTime.isDefined || i.price.recurring.isDefined =>
        i.price.oneTime.map(_.currency) getOrElse i.price.recurring.get.currency
    }

    c getOrElse "CAD"
  }

  def checkCurrency (cur:String) =
    if(items.size > 0) cur == currency
    else true

  /** one time total  */
  def total : Price = {
    var sum1 =
      items.flatMap(_.price.oneTime.toList).map(_.amount).sum
    Price (sum1, currency)
  }

  /** recurring total */
  def rectotal : Price = {
    var sum1 =
      items.flatMap(_.price.recurring.toList).map(_.amount).sum
    Price (sum1, currency)
  }

  def findAcct = Acct.createOrFind(userId, clubWid.wid.get)
}

/**
  * an item in the cart
  */
case class CartItem (
  desc : String,           // what
  entLink : String,        // link to entity
  entQuery : String,       // identify entity
  doneAction : String,     //create/edit/delete
  cancelAction : String,   //create/edit/delete
  price:ItemPrice = ItemPrice(),
  recInterval:Option[String] = None,
  props : Map[String,String] = Map.empty,
  state : String = Cart.STATE_CREATED,
  quantity: Option[Int] = None,
  category : Option[String] = None,
  _id : ObjectId = new ObjectId()
  ) {
}

/** price abstraction */
case class Price (
  amount : Float,
  currency : String = "CAD"
  ) {
  override def toString: String = amount + " " + currency

  def + (other : Price) = {
    if(other.currency != this.currency) throw new IllegalArgumentException("currency does not match")
    Price (amount + other.amount, this.currency)
  }

  def - (other : Price) = {
    if(other.currency != this.currency) throw new IllegalArgumentException("currency does not match")
    Price (amount - other.amount, this.currency)
  }

  def < (other : Price) = {
    if(other.currency != this.currency) throw new IllegalArgumentException("currency does not match")
    amount < other.amount
  }

  def orZero =
    if(amount < 0) this.copy(amount=0)
    else this

  def reverse = Price (0 - amount, this.currency)
}

/** price of an item */
case class ItemPrice (
  oneTime : Option[Price] = None,
  recurring : Option[Price] = None,
  interval : Option[Price] = None
  ) {
  override def toString: String =
    oneTime.mkString
}

/** utilities */
object Cart {
  final val STATE_CREATED = "open.created"
  final val STATE_CHECKEDOUT = "open.checkedout"
  final val STATE_PAID = "done.paid"
  final val STATE_CANCEL = "done.cancelled"
  final val STATE_REFUNDED = "done.refunded"

  def createOrFind (userId:ObjectId, club:UWID) : Cart = {
    ROne[Cart] ("userId" -> userId, "archived" -> false, "clubWid" -> club.grated).getOrElse{
      val c = Cart (userId, club)
      c.create(razie.db.tx.auto)
      c
    }
  }

  def find (userId:ObjectId) =
    ROne[Cart] ("userId" -> userId, "archived" -> false)

  def findById (id:ObjectId) =
    ROne[Cart] ("_id" -> id, "archived" -> false)

  def list (userId:ObjectId) =
    RMany[Cart] ("userId" -> userId)

  def cartCanceled (id:ObjectId): Unit = {
  }
}


