/**
  * the mod rk is to allow parents to register RK/kidz for any topic / events etc
  */
package controllers

import mod.snow._
import razie.wiki.model.{UWID, WID}

import scala.Array.canBuildFrom
import scala.Array.fallbackCanBuildFrom
import scala.Option.option2Iterable
import org.bson.types.ObjectId
import razie.db.REntity
import razie.db.RMany
import razie.db.ROne
import razie.db.RTable
import razie.wiki.Sec.EncryptedS
import model.User
import play.api.data.Form
import play.api.data.Forms.nonEmptyText
import play.api.data.Forms.number
import play.api.data.Forms.text
import play.api.data.Forms.tuple
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.Request
import razie.{Audit, Logging, clog, cout}
import admin.Config
import mod.cart._
import razie.db.RMongo
import play.api.mvc.AnyContent
import play.api.mvc.Result
import razie.db.RDelete
import razie.diesel.dom.ECtx
import razie.diesel.ext.{EExecutor, EMsg, EVal, MatchCollector}
import razie.wiki.dom.WikiDomain

import scala.collection.mutable.ListBuffer

/** per topic reg */
case class ModRkReg
(
  wid: WID,
  curYear: String = Config.curYear) {

  lazy val kids =
    RMany[ModRkEntry]("wpath" -> wid.wpath).toList ++ // todo purge old recs
      wid.uwid.toList.flatMap { uwid =>
        RMany[ModRkEntry]("uwid.cat" -> uwid.cat, "uwid.id" -> uwid.id).toList
      }
}

/** per topic reg */
@RTable
case class ModRkEntry
(
  rkId: ObjectId,
  wpath: Option[String], // todo for old records - purge them and code at some point
  uwid: Option[UWID] = None, // for new records
  role: String,
  note: Option[String] = None,
  state: Option[String] = None, // "cart" or "paid"
  attended: Option[String] = None, // any value means yes basically
  curYear: String = Config.curYear, // just a notional value, UWID matters
  forms: Seq[RoleWid] = Seq.empty, // future possiblity to have reg forms per event
  _id: ObjectId = new ObjectId) extends REntity[ModRkEntry] {

  // current role/state to display
  def current = state match {
    case None => role
    case Some(ModRk.STATE_PAID) => role
    case Some(ModRk.STATE_INCART) => """<a href="/doe/cart" class="btn btn-warning">In shopping cart</a>"""
  }

  // optimize access to User object
  lazy val rk = rkId.as[RacerKid]
}

/** controller for club management */
object ModRk extends RazController with Logging {

  final val STATE_INCART = "incart"
  final val STATE_PAID = "paid"
  final val STATE_REFUNDED = "refunded"

  import razie.db.RMongo.as

  def doeModRkRegs(wid: WID) = FAUR { implicit request =>
    (for (
      page <- wid.page orErr s"no page $wid";
      club <- wid.parentOf(WikiDomain(wid.getRealm).isA("Club", _)).flatMap(Club.apply) orErr "Club not found";
      isa <- club.membership(request.au.get._id).isDefined  orCorr cNotMember(club.name)
    ) yield {
      val reg = Regs.findClubUserYear(club.wid, request.au.get._id, club.curYear)

      // already regd for event
      val regd = ModRkReg(wid).kids.map (x=>
        (x, x.rkId.as[RacerKid])
      ).collect {
        // filter out incoherent records
        case (x,y) if y.isDefined => (x, y.get)
      }

      // all assoc'd kids
      // todo filter out incoherent records
      val rka = new ListBuffer[RacerKidAssoc]()
      RacerKidz.rka(request.au.get).foreach{x=>
        // only show real kids and spouse, not people otherwise associated with me (like guest-pro)
        if(rka.find(_.to == x.to).isEmpty && x.assoc != RK.ASSOC_LINK) rka append x
      }
      val rks = rka.map(x=>
        (
          x,
          x.rk.get,
          reg.flatMap(_.roleOf(x.to)).orElse(club.membership(request.au.get._id).map(_.role)  )
        )).toList

      ROK.k noLayout {
        views.html.modules.doeModRkRegs(request.au.get, page, ModRkReg(page.wid), regd, rks)
      }
    }) getOrElse Msg2("CAN'T see Registrations " + errCollector.mkString)
  }

  //post
  def doeModRkAdd(wid: WID, rkid: String, role: String) = FAUR { implicit stok =>
    (for (
      club <- wid.parentOf(WikiDomain(wid.getRealm).isA("Club", _)).flatMap(Club.apply) orErr "Club not found";
      ism <- club.isMember(stok.au.get) orCorr cNotMember(club.name)
    ) yield razie.db.tx("doeModRkAdd", stok.userName) { implicit txn =>
      var e = ModRkEntry(new ObjectId(rkid), None, wid.uwid, role)
      if (wid.page.get.attr("price").exists(_.trim.length > 0) &&
          !wid.page.get.attr("module.reg-excluded").exists(_.split(",") contains role)
      ) {
        val price = wid.page.get.attr("price").get.trim.toFloat
        e = e.copy(state = Some(STATE_INCART))
        val cart = Cart.createOrFind(stok.au.get._id, club.uwid)
        val name = RacerKidz.findByIds(rkid).map(_.info.ename).getOrElse("??")
        cart.add(CartItem(
          s"Register $name for ${wid.page.get.label}",
          wid.urlRelative(stok.realm),
          wid.wpath + rkid,
          s"""$$msg modrk.updstatus(wpath="${wid.wpath}", rkid="$rkid")""",
          s"""$$msg modrk.remove(wpath="${wid.wpath}", rkid="$rkid")""",
          ItemPrice(Some(Price(price)))
        ))
      }
      e.create
      Redirect(Wiki.w(wid))
    }) getOrElse unauthorizedPOST()
  }

  /** remove a reg record and refund credits if Admin and paid */
  def doeModRkRemove(wid: WID, rkid: String) = FAUR { implicit stok =>
    var msg = ""
    razie.db.tx("doeModRkRemove", stok.userName) { implicit txn =>
      wid.uwid.map { uwid =>
        ROne[ModRkEntry]("rkId" -> new ObjectId(rkid), "uwid.cat" -> uwid.cat, "uwid.id" -> uwid.id).foreach { reg =>

          if (reg.state.exists(_ == STATE_PAID)) {

            msg = "CANNOT remove paid item"

            if (stok.au.exists(_.canAdmin(wid))) {

              // find cart item and refund credit price

              // todo optimize to use rkid owner as cart user id
              if (
                (for (
                  cart <- RMany[Cart]("state" -> "done.paid");
                  item <- cart.items.find(i => i.entQuery == wid.wpath + rkid && i.state == Cart.STATE_PAID)
                ) yield {
                  Carts.irefundItem(cart, item._id.toString, stok.au.get)
                  msg = "REFUNDED paid item"
                }
                  ).toList.size <= 0) {
                msg = "can't find cart or item: " + stok.errCollector.mkString
              }

            }

          } else
            reg.delete

          // if there is a cart -
          if (reg.state.exists(_ == STATE_INCART))
            Cart.find(stok.au.get._id).map { cart =>
              cart.rm(wid.wpath + rkid)
            }

          //todo delete forms too, if any
        }
      }

      // try also old records with wpath
      ROne[ModRkEntry]("rkId" -> new ObjectId(rkid), "wpath" -> wid.wpath).foreach(_.delete)

      if (msg.length > 0)
        Msg(msg, wid)
      else
        Redirect(Wiki.w(wid))
    }
  }
}


object ModRkExec extends EExecutor("modrk") {

  override def isMock: Boolean = true

  override def test(m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == "modrk"
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    razie.db.tx("ModRkExec", "?") { implicit txn =>
      in.met match {

        case "updstatus" => {
          //todo auth
          var msg = "ok"
          clog << "modrk.remove"
          val wpath = ctx("wpath")
          val rkid = ctx("rkid")

          WID.fromPath(wpath).flatMap(_.uwid).map {
            uwid =>
              ROne[ModRkEntry](
                "rkId" -> new ObjectId(rkid), "uwid.cat" -> uwid.cat, "uwid.id" -> uwid.id).foreach {
                reg =>
                  if (reg.state.exists(_ == ModRk.STATE_PAID)) {
                    msg = "CANNOT update paid item"
                  } else
                    reg.copy(state = Some(ModRk.STATE_PAID)).update
              }
          }
          List(new EVal("result", msg))
        }

        case "remove" => {
          //todo auth
          var msg = "ok"
          clog << "modrk.remove"
          val wpath = ctx("wpath")
          val rkid = ctx("rkid")

          WID.fromPath(wpath).flatMap(_.uwid).map {
            uwid =>
              ROne[ModRkEntry](
                "rkId" -> new ObjectId(rkid), "uwid.cat" -> uwid.cat, "uwid.id" -> uwid.id).foreach {
                reg =>
                  if (reg.state.exists(_ == ModRk.STATE_PAID)) {
                    msg = "Removed paid item !!"
                    razie.base.Audit.logdb("MODRK_DEL", "Removed paid record", reg.grated.toString)
                    reg.delete
                  } else
                    reg.delete
              }
          }
          List(new EVal("result", msg))
        }

        case _ => {
          Nil
        }
      }
    }
  }

  override def toString = "$executor::modrk "
}

