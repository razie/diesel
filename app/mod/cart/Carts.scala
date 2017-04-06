package mod.cart

import admin.Config
import akka.actor.{Actor, Props}
import controllers._
import mod.diesel.model.DieselMsgString
import model._
import org.bson.types.ObjectId
import org.scalatest.path
import play.api.mvc.{Action, Result}
import razie.base.Audit
import razie.db.{REntity, ROne, Txn, tx}
import razie.wiki.dom.WikiDomain
import razie.wiki._
import razie.wiki.model._
import razie.{Logging, clog, cout}
import views.html.modules.cart.doeCart

import scala.Option.option2Iterable
import scala.concurrent.Future
import scala.util.Try

/** controller for club management */
object Carts extends RazController with Logging {

  def cart() = FAUR { implicit request =>
    (for (
      cart <- request.au.map(_._id).flatMap(Cart.find);
      cwid <- cart.clubWid.wid orErr "club wid not";
      club <- Club(cwid) orErr "Club not found";
      acct <- Acct.createOrFind(cart.userId, cwid) orErr "Need registration up to date!"
    ) yield {
      ROK.r apply {
        doeCart(cart, acct, club.props)
      }
    }) getOrElse {
      unauthorized("no active cart...", false)
    }
  }

  // process a refund of a paid item
  def irefundItem(cart: Cart, itemId: String, user: User)(implicit txn:Txn) = {
    var done = false
    clog << s"CARTS - processing refund itemId=$itemId"

    val item = cart.items.find(item => item._id.toString == itemId && item.state == Cart.STATE_PAID)
    item.map { item =>
      clog << "CARTS - item found - process refund"

      val newCart = cart.copy(
        items = cart.items.map(x =>
          if (x._id == item._id) item.copy(state = Cart.STATE_REFUNDED) else x
        )
      )

      item.price.oneTime.map { price =>
        cart.findAcct.map { acct =>
          val newAcct = acct.add(AcctTxn(
            user.userName,
            user._id,
            acct._id,
            price,
            "refund",
            "refund - " + item.desc,
            Acct.STATE_REFUND
          ))

          newAcct.update
          newCart.update
          Services ! DieselMsgString(item.cancelAction, Nil, Nil)
          done = true
          clog << "CARTS - processed refund"
        }
      }
    }

    done
  }

  def rmitem(id: String) = FAUR { implicit request =>
    request.au.map(_._id).flatMap(Cart.find).map { cart =>
      val item = cart.items.find(_._id.toString == id)
      if (item.exists(_.state != Cart.STATE_PAID)) {
        val c = cart.copy(items = cart.items.filter(_._id.toString != id))
        item.map { i =>
          Services ! DieselMsgString(i.cancelAction, Nil, Nil)
        }

        razie.db.tx("rmitem", request.userName) { implicit txn =>
          c.update
        }
      }

      Redirect(routes.Carts.cart())
    } getOrElse {
      unauthorized("no active cart...", false)
    }
  }

  def cartpaid(id: String) = FAUR { implicit request =>
    Cart.findById(new ObjectId(id)).filter(_.state != Cart.STATE_PAID).map { cart =>
      razie.db.tx("cartpaid", request.userName) { implicit txn =>
        val c = cart.copy(
          items = cart.items.map(_.copy(state = Cart.STATE_PAID)),
          state = Cart.STATE_PAID,
          archived = true
        )

        val what = request.formParm("what")
        var prebal = Price(0)
        var postbal = Price(0)

        try {
          var acct = Acct.createOrFind(cart.userId, cart.clubWid.wid.get).get
          prebal = acct.balance

          val paid = Price(
            Try {
              request.formParm("amount").toFloat
            } getOrElse 0,
            request.formParm("currency")
          )

          acct = acct.add(AcctTxn(
            request.au.get.userName,
            request.au.get._id,
            acct._id,
            Price(-cart.total.amount, cart.currency),
            "debit",
            "cart checked out " + what,
            Acct.STATE_CHECKEDOUT
          ))

          acct = acct.add(AcctTxn(
            request.au.get.userName,
            request.au.get._id,
            acct._id,
            Price(paid.amount, paid.currency),
            "credit",
            "payment " + what,
            Acct.STATE_PAID
          ))

          postbal = acct.balance
        } catch {
          case t: Throwable =>
            Audit.logdb("ERR_BILLING", "ERR_PAYPAL_REC", t)
        }

        val actxn = AcctPayTxn(
          request.au.get.userName,
          request.au.get._id,
          cart._id,
          request.formParm("id"),
          request.formParm("amount"),
          request.formParm("currency"),
          cart.total,
          prebal,
          postbal
        )

        actxn.create
        Audit.logdb("BILLING", "CART_PAID." + what, txn)

        c.items.map { i =>
          Services ! DieselMsgString(i.doneAction, Nil, Nil)
        }

        c.update

        Ok("ok") //Redirect(routes.Carts.cart())
      }
    } getOrElse {
      unauthorized("no active cart...", false)
    }
  }

  def cartcancel(id: String) = FAUR { implicit request =>
    Cart.findById(new ObjectId(id)).filter(_.state != Cart.STATE_PAID).map { cart =>
      razie.db.tx("cartcancel", request.userName) { implicit txn =>
        val c = cart.copy(
          items = cart.items.map(_.copy(state = Cart.STATE_CANCEL)),
          state = Cart.STATE_CANCEL,
          archived = true
        )

        c.items.map { i =>
          Services ! DieselMsgString(i.cancelAction, Nil, Nil)
        }

        Audit.logdb("CART_CANCEL", request.au.map(_.userName).mkString, id)

        c.update

        Redirect(routes.Carts.cart())
      }
    } getOrElse {
      unauthorized("no active cart...", false)
    }
  }

}

/** an actual payment, recorded here as well to have an audit trail for actual moneys.
  *
  * credits are only tracked as AcctTxn
  */
case class AcctPayTxn
(
  userName: String,
  userId: ObjectId,
  cartId: ObjectId,
  paypalId: String,
  paypalAmount: String,
  paypalCurrency: String,
  cartBalance: Price,
  preactBalance: Price,
  postactBalance: Price,
  _id: ObjectId = new ObjectId
) extends REntity[AcctPayTxn]

