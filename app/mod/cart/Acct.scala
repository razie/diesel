package mod.cart

import controllers.Club
import model.Users
import org.bson.types.ObjectId
import org.joda.time.DateTime
import razie.audit.Audit
import razie.db._
import razie.wiki.model.{UWID, WID}

/**
  * an account maintains a balance and a list of transactions, per user per club/group
  */
case class Acct
(
  userName  : String,
  userId  : ObjectId,
  regId  : Option[ObjectId], // attached to a reg id
  clubWid : UWID,            // and/or a club
  balance : Price = Price(0, "CAD"),
  discount : Option[Int] = None,
  lastTxnId : Option[ObjectId] = None,
  props : Map[String,String] = Map.empty,
  _id     : ObjectId = new ObjectId()
  ) extends REntity[Acct] {

  def txns = RMany[AcctTxn] ("acctId" -> _id).toList.sortWith(_.crDtm isBefore _.crDtm)

  def loadBalance = Acct.findById(_id).get.balance

  def add (t:AcctTxn)(implicit txn:Txn) = {
    val a = this.copy(balance = balance + t.amount, lastTxnId = Some(t._id))
    Audit.logdb("BILLING", t.what.toUpperCase, "Balance = "+a.balance, t)
    t.create
    a.update
    a
    // todo check that the previous one was done - update lastTxnId and check that it was last
  }

  /** calc discount to substrct from total */
  def calcDiscount (cartTotal : Price) = {
    var total = cartTotal.amount
    total = discount.map(_ * cartTotal.amount/100).getOrElse(0)
    Price(total, cartTotal.currency)
  }

  def payable (cartTotal : Price) = {
    var total = cartTotal.amount

    // first apply discounts
    total = discount.map(total - _ * total/100).getOrElse(total)

    // then deduct credit balance
    if(total > balance.amount) total = total - balance.amount else 0

    Price(total, cartTotal.currency)
  }

  lazy val user = Users.findUserById(userId)
}

/**
  *  a transaction on one account - it may or may not change the ballance
  *
  *  a txn once created cannot be changed. Do a refund or adjustment if you screw up something
  */
case class AcctTxn
(
  userName : String,
  userId : ObjectId,
  acctId  : ObjectId,
  amount : Price,
  what : String,           // credit/refund/debit/adjustment
  desc : String,           // what
  state : String = Acct.STATE_CREATED,
  crDtm : DateTime = DateTime.now,
  _id : ObjectId = new ObjectId()
  ) extends REntity[AcctTxn] {
}

/** Account utilities */
object Acct {
  final val STATE_CREATED    = "open.created"
  final val STATE_CHECKEDOUT = "open.checkedout"
  final val STATE_PAID       = "done.paid"
  final val STATE_CANCEL     = "done.cancelled"
  final val STATE_CREDIT     = "done.credit"
  final val STATE_REFUND     = "done.refund"

  // if None, means no registration found
  def createOrFind (userId:ObjectId, clubWid:WID) : Option[Acct] = {
    Club(clubWid).map { club=>
      ROne[Acct]("userId" -> userId, "clubWid" -> club.uwid.grated).getOrElse {
        val curr = club.props.getOrElse("currency", "CAD")
          val c = Acct(Users.nameOf(userId), userId, None, clubWid.uwid.get, Price(0, curr))
          c.create(tx.auto)
          c
      }
    }
  }

  def findCurrent (userId:ObjectId, clubWid:WID) = {
    Club(clubWid).flatMap { club=>
      ROne[Acct]("userId" -> userId, "clubWid.cat" -> club.uwid.cat, "clubWid.id" -> club.uwid.id)
    }
  }

  def findById (id:ObjectId) = ROne[Acct] ("_id" -> id)
}

