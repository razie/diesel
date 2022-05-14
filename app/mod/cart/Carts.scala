package mod.cart

import com.google.inject.{Inject, Singleton}
import controllers._
import model._
import org.bson.types.ObjectId
import play.api.Configuration
import razie.Logging
import razie.audit.Audit
import razie.db.{REntity, Txn}
import razie.diesel.model.DieselMsgString
import razie.wiki._
import razie.wiki.model._
import scala.Option.option2Iterable
import scala.util.Try
import views.html.modules.cart.doeCart

/** controller for club management */
@Singleton
class Carts @Inject()(config:Configuration) extends RazController with Logging {
//object Carts extends RazController with Logging {

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

  def rmitem(id: String) = FAUR { implicit request =>
    request.au.map(_._id).flatMap(Cart.find).map { cart =>
      val item = cart.items.find(_._id.toString == id)
      if (item.exists(_.state != Cart.STATE_PAID)) {
        val c = cart.copy(items = cart.items.filter(_._id.toString != id))
        item.map { i =>
          Services ! DieselMsgString(i.cancelAction)
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
        var c = cart.copy(
          items = cart.items.map(_.copy(state = Cart.STATE_PAID)),
          state = Cart.STATE_PAID,
          archived = true
        )

        val what = request.formParm("what")
        val paymentId = request.formParm("id")
//          val userName" -> request.au.get.userName,
//          "userId" -> request.au.get._id.toString,
//        "paymentAmount" -> request.formParm("amount"),
//        "paymentCurrency" -> request.formParm("currency")

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

          c = c.copy(
            discount = acct.discount
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
            s"payment $what - $paymentId",
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

        // todo could validate the payment ID - if what=="PayPal" then id ~= paypalId : PAY-0G0662193T022332ELBXJPRA

        actxn.create
        Audit.logdb("BILLING", "CART_PAID." + what, txn)

        c.items.map { i =>
          Services ! DieselMsgString(i.doneAction).withContext(
            Map(
              "userName" -> request.au.get.userName,
              "userId" -> request.au.get._id.toString,
              "paymentId" -> request.formParm("id"),
              "paymentAmount" -> request.formParm("amount"),
              "paymentCurrency" -> request.formParm("currency")
            )
          )
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
          Services ! DieselMsgString(i.cancelAction)
        }

        Audit.logdb("CART_CANCEL", request.au.map(_.userName).mkString, id)

        c.update

        Redirect(routes.Carts.cart())
      }
    } getOrElse {
      unauthorized("no active cart...", false)
    }
  }

  // user starting to pay from reg: create cart and redirect to it
  def addToCart(clubWpath: String) = FAUR { implicit stok =>
    (for (
      wid <- WID.fromPath(clubWpath) orErr "no wid";
      uwid <- wid.uwid orErr "no uwid";
      pCategory <- stok.fqhParm("category") orErr "api: no category";
      pDesc <- stok.fqhParm("desc") orErr "api: no desc";
      pLink <- stok.fqhParm("link") orErr "api: no link";
      pOk <- stok.fqhParm("ok") orErr "api: no ok";
      pPrereq <- stok.fqhParm("prereq").orElse(Some(""));
      pCancel <- stok.fqhParm("cancel") orErr "api: no cancel";
      pAmount <- stok.fqhParm("amount") orErr "api: no amount";
      pRecAmount <- stok.fqhParm("recamount") orErr "api: no recamount";
      pCurrency <- stok.fqhParm("currency") orErr "api: no currency";
      pId <- stok.fqhParm("id") orErr "api: no id"
    ) yield razie.db.tx("addToCart", stok.userName) { implicit txn =>
      // remove any other of the same category (i.e. registration)
      val pCartRedirect = stok.fqParm("cartRedirect", "");

      var cart = Cart.createOrFind(stok.au.get._id, uwid)
      cart = cart.copy(items = cart.items.filterNot(_.category.exists(_ == pCategory && pCategory.length > 0)))

      if(pCartRedirect.length > 0)
        cart = cart.copy(cartRedirect = Some(pCartRedirect))

      val isShould = true
//      pPrereq.isEmpty || {
//        DieselMsgString(pPrereq).withContext(
//          Map(
//            "userName" -> stok.au.get.userName,
//            "userId" -> stok.au.get._id.toString,
//            "paymentId" -> stok.formParm("id"),
//            "paymentAmount" -> stok.formParm("amount"),
//            "paymentCurrency" -> stok.formParm("currency")
//          )
//        )
//      }

      // todo enable subscriptions
      // see https://developer.paypal.com/docs/subscriptions/integrate/#3-create-a-plan
      // and https://developer.paypal.com/docs/api/quickstart/create-webhook/#
      val rec = None //if(pRecAmount.toFloat == 0) None else Some(Price(pRecAmount.toFloat, pCurrency))

      cart.add(CartItem(
        pDesc,
        pLink,
        pId,
        pOk,
        pCancel,
        ItemPrice(
          Some(Price(pAmount.toFloat, pCurrency)),
          rec
        )
      )
        .copy(category=if(pCategory.length > 0) Some(pCategory) else None)
      )

      val pRedirect = stok.fqParm("redirect", "");

      if (pRedirect.length > 0)
        Ok(pRedirect)
      else
        Ok(mod.cart.routes.Carts.cart().url)
    }) getOrElse unauthorized("haha")
  }

}

object Carts extends RazController with Logging {
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
          Services ! DieselMsgString(item.cancelAction)
          done = true
          clog << "CARTS - processed refund"
        }
      }
    }

    done
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

