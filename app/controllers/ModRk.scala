/**
  * the mod rk is to allow parents to register RK/kidz for any topic / events etc
  */
package controllers

import mod.cart._
import mod.snow._
import org.bson.types.ObjectId
import razie.db.{REntity, RMany, ROne, RTable}
import razie.diesel.dom.WikiDomain
import razie.diesel.engine.DomAst
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes.{EMsg, EVal, MatchCollector}
import razie.diesel.expr.ECtx
import razie.wiki.{Config, Services}
import razie.wiki.model.{UWID, WID}
import razie.{Logging, audit, clog}
import scala.Option.option2Iterable
import scala.collection.mutable.ListBuffer

/** in a way this module is deprecated - need to see if still used anywhere. Persons register for stuff - per topic reg */

/**  per topic reg */
case class ModRkReg
(
  wid: WID,
  curYear: String = Services.config.curYear) {

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
  curYear: String = Services.config.curYear, // just a notional value, UWID matters
  forms: Seq[RoleWid] = Seq.empty, // future possiblity to have reg forms per event
  _id: ObjectId = new ObjectId) extends REntity[ModRkEntry] {

  // current role/state to display
  def current = state match {
    case None => role
    case Some(ModRk.STATE_PAID) => role
    case Some(ModRk.STATE_INCART) => """<a href="/doe/cart" class="btn btn-warning">In shopping cart</a>"""
    case _ => throw new IllegalArgumentException("no match for: "+state)
  }

  // optimize access to User object
  lazy val rk = rkId.as[RacerKid]
}

/** controller for club management */
import com.google.inject.{Inject, Singleton}
@Singleton
class ModRk extends RazController with Logging {

import ModRk._

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
    }) getOrElse Msg("CAN'T see Registrations " + errCollector.mkString)
  }

  //post
  def doeModRkAdd(wid: WID, rkid: String, role: String) = FAUR { implicit stok =>
    (for (
      club <- wid.parentOf(WikiDomain(wid.getRealm).isA("Club", _)).flatMap(Club.apply) orErr "Club not found";
      ism <- club.isMember(stok.au.get) orCorr cNotMember(club.name)
    ) yield razie.db.tx("doeModRkAdd", stok.userName) { implicit txn =>
      var e = ModRkEntry(new ObjectId(rkid), None, wid.uwid, role)
      if (wid.page.get.attr("price").exists(_.trim.length > 0) &&
        !wid.page.get.findAttr("module.reg-excluded").exists(_.split(",") contains role)
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
      Redirect(wid.w)
    }) getOrElse unauthorizedPOST()
  }

  /** remove a reg record and refund credits if Admin and paid */
  def doeModRkRemove(wid: WID, rkid: String) = FAUR { implicit stok =>
    log (s"doeModRkRemove: wid=${wid.wpath} rkid=$rkid")

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
        Redirect(wid.w)
    }
  }
}


object EEModRkExec extends EExecutor("modrk") {

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
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
          List(new EVal("payload", msg))
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
                    audit.Audit.logdb("MODRK_DEL", "Removed paid record", reg.grated.toString)
                    reg.delete
                  } else
                    reg.delete
              }
          }
          List(new EVal("payload", msg))
        }

        case _ => {
          Nil
        }
      }
    }
  }

  override def toString = "$executor::modrk "
}

object ModRk extends RazController with Logging {

  final val STATE_INCART = "incart"
  final val STATE_PAID = "paid"
  final val STATE_REFUNDED = "refunded"
}