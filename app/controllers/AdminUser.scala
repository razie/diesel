package controllers

import com.google.inject.Singleton
import java.lang.management.{ManagementFactory, OperatingSystemMXBean}
import java.lang.reflect.Modifier

import akka.cluster.Cluster
import com.mongodb.casbah.Imports.{DBObject, IntOk}
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.Imports._
import com.novus.salat._
import com.novus.salat.grater
import difflib.DiffUtils
import mod.notes.controllers.NotesLocker
import mod.snow.RK
import org.json.{JSONArray, JSONObject}
import play.api.libs.concurrent.Akka
import play.api.libs.json.JsObject
import play.twirl.api.Html
import razie.db.{RCreate, RMany, RazMongo, WikiTrash}
import razie.db.RazSalatContext.ctx
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, number}
import play.api.mvc.{Action, AnyContent, Request, Result}
import razie.g.snakked
import razie.{cout, js}
import razie.wiki.{Enc, Services}
import razie.wiki.model.{Perm, WID, WikiEntry, Wikis}
import razie.wiki.admin.{GlobalData, MailSession, SendEmail}
import razie.audit.ClearAudits
import model.{User, Users, WikiScripster}
import x.context
import razie.hosting.Website

import scala.util.Try
import razie.Snakk._
import razie.audit.{Audit, ClearAudits}
import razie.wiki.Sec._
import razie.wiki.util.DslProps

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

@Singleton
class AdminUser extends AdminBase {
  val ADUSER = routes.AdminUser.user(_)

  def user(id: String) =
    FADR { implicit stok =>
      ROK.r admin { implicit stok => views.html.admin.adminUser(model.Users.findUserById(id).map(_.forRealm(stok.realm))) }
    }

  def userDelete1(id: String) =
    FAD { implicit au => implicit errCollector => implicit request =>
      ROK.r admin { implicit stok => views.html.admin.adminUserDelete(model.Users.findUserById(id)) }
    }

  def userDelete2(id: String) =
    FAD { implicit au => implicit errCollector => implicit request =>
      val uid = new ObjectId(id)
      var res:List[String] = Nil

      razie.db.tx("udelete2", au.userName) { implicit txn =>

        val tod = ListBuffer[(String, String, ObjectId)]()
        def del (table:String, field:String, id:ObjectId) = {
          tod.append((table, field, id))
        }

        RazMongo("User").findOne(Map("_id" -> uid)).foreach { u =>
          WikiTrash("User", u, auth.get.userName, txn.id).create
          del("User", "_id", uid)
          del("UserOld", "_id", uid)
        }

        del("UserQuota", "userId",  uid)
        del("UserWiki", "userId", uid)
        del("UserEvent", "userId", uid)
        del("Comment", "userId", uid)
        del("ParentChild", "parentId", uid)
        del("ParentChild", "childId", uid)

        RazMongo("Acct").find(Map("userId" -> uid)).foreach { a =>
          del("Acct", "userId", uid)
          del("AcctTxn", "acctId", a._id.get)
          del("AcctTxn", "userId", uid)
        }
        del("Cart", "userId", uid)
        del("AcctPayTxn", "userId", uid)

        del("AutoSave", "userId", uid)
        // leave Comment
        del("EditLock", "uid", uid)

        del("Progress", "ownerId", uid)

        RazMongo("Profile").findOne(Map("userId" -> uid)).foreach { u =>
          WikiTrash("Profile", u, auth.get.userName, txn.id).create
          del("Profile", "userId", (uid))
        }

        del("Inbox", "toId", uid)
        del("Inbox", "fromId" ,uid)
        del("NoteShare", "toId", uid)
        del("NoteShare", "ownerId", uid)
        del("NoteContact", "uid", uid)
        del("NoteContact", "oid", uid)
        del("weNote", "by" ,uid)
        del("weForm", "by" ,uid)

        (RazMongo("RacerKidAssoc").find(Map("from" -> (uid))).toList :::
          RazMongo("RacerKidAssoc").find(Map("owner" -> (uid))).toList).foreach { u =>
//          WikiTrash("RacerKidAssoc", u, auth.get.userName, txn.id).create
          del("RacerKidAssoc", "_id", u._id.get)
          del("VolunteerH", "rkaId", u._id.get)
        }

        RazMongo("RacerKidInfo").find(Map("ownerId" -> (uid))).foreach { u =>
//          WikiTrash("RacerKidInfo", u, auth.get.userName, txn.id).create
          del("RacerKidInfo", "_id", u._id.get)
        }

        (RazMongo("RacerKid").find(Map("userId" -> (uid))).toList :::
          RazMongo("RacerKid").find(Map("ownerId" -> (uid))).toList).foreach { u =>
//          WikiTrash("RacerKid", u, auth.get.userName, txn.id).create
          del("RacerKid", "_id", u._id.get)
          del("RkHistoryFeed", "rkId", u._id.get)
          del("RkHistory", "rkId", u._id.get)
          del("ModRkEntry", "rkId", u._id.get)
          del("RacerKidWikiAssoc", "rkId", u._id.get)
          del("RacerKidInfo", "rkId", u._id.get)
        }

        res = tod.toList.map {t=>
          razie.cout << "Removing: " + t.toString
          RazMongo(t._1).remove(Map(t._2 -> t._3))
          "Removing: " + t.toString
        }

      }
      Ok(res.mkString("\n"))
    }

  def userStatus(id: String, s: String) = FADR { implicit stok =>
      (for (
        goodS <- s.length == 1 && ("as" contains s(0)) orErr ("bad status");
        u <- Users.findUserById(id)
      ) yield {
          Profile.updateUser(u, u.setStatus(stok.realm, s))
          Redirect(ADUSER(id))
        }) getOrElse {
        error("ERR_ADMIN_CANT_UPDATE_USER ustatus " + id + " " + errCollector.mkString)
        unauthorized("ERR_ADMIN_CANT_UPDATE_USER ustatus " + id + " " + errCollector.mkString)
      }
    }

  def su(id: String) = FADR { implicit stok=>
    val au = stok.au.get
      (for (
        u <- Users.findUserById(id)
      ) yield {
          Audit.logdb("ADMIN_SU", u.userName)
          Application.razSu = au.email
          Application.razSuTime = System.currentTimeMillis()
          Redirect("/").withSession(
            Services.config.CONNECTED -> Enc.toSession(u.email),
            "extra" -> au.email
          )
        }) getOrElse {
        error("ERR_ADMIN_CANT_UPDATE_USER su " + id + " " + errCollector.mkString)
        unauthorized("ERR_ADMIN_CANT_UPDATE_USER su " + id + " " + errCollector.mkString)
      }
    }

  val OneForm = Form("val" -> nonEmptyText)

  case class AddPerm(perm: String)

  val permForm = Form {
    mapping(
      "perm" -> nonEmptyText.verifying(
        "starts with +/-", a => ("+-" contains a(0))).verifying(
        "known perm", a => Perm.all.contains(a.substring(1))))(AddPerm.apply)(AddPerm.unapply)

  }

  def userPerm(id: String) =
    FAD { implicit au => implicit errCollector => implicit request =>
      val realm = Website.getRealm

      permForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that perm!"), {
        case we@AddPerm(perm) =>
          (for (
            goodS <- ("+-" contains perm(0)) && Perm.all.contains(perm.substring(1)) orErr ("bad perm");
            u <- Users.findUserById(id);
            pro <- u.profile
          ) yield {
              // remove/flip existing permission or add a new one?
            val sperm = perm.substring(1)

              u.update{
                val ur = u.forRealm(realm)

                if (perm(0) == '-' && (ur.perms.contains("+" + sperm))) {
                  u.removePerm(realm, "+" + sperm)
                } else if (perm(0) == '+' && (ur.perms.contains("-" + sperm))) {
                  u.removePerm(realm, "-" + sperm)
                } else u.addPerm(realm, perm)
              }

              cleanAuth(Some(u))
              Redirect(ADUSER(id))
            }) getOrElse {
            error("ERR_ADMIN_CANT_UPDATE_USER uperm " + id + " " + errCollector.mkString)
            Unauthorized("ERR_ADMIN_CANT_UPDATE_USER uperm " + id + " " + errCollector.mkString)
          }
      })
    }

  val quotaForm = Form(
    "quota" -> number(-1, 1000, true))

  def userQuota(id: String) =
    FAD { implicit au => implicit errCollector => implicit request =>
      quotaForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't add that quota!"), {
        case quota =>
          (for (
            u <- Users.findUserById(id);
            pro <- u.profile
          ) yield {
              // remove/flip existing permission or add a new one?
              u.quota.reset(quota)
              Redirect(ADUSER(id))
            }) getOrElse {
            error("ERR_ADMIN_CANT_UPDATE_USER.uquota " + id + " " + errCollector.mkString)
            Unauthorized("ERR_ADMIN_CANT_UPDATE_USER.uquota " + id + " " + errCollector.mkString)
          }
      })
    }

  def userModnotes(id: String) = FADR { implicit stok =>
    OneForm.bindFromRequest.fold(
    formWithErrors =>
      Msg2(formWithErrors.toString + "Oops, can't add that note!"), {
      case uname =>
        (for (
          u <- Users.findUserById(id);
          pro <- u.profile
        ) yield {
            var ok=true
            razie.db.tx("umodnote", stok.au.get.userName) { implicit txn =>
                if(uname startsWith "+")
                  Profile.updateUser(u, u.addModNote(stok.realm, uname))
                else if(uname startsWith "-")
                  Profile.updateUser(u, u.removeModNote(stok.realm, uname))
                else
                  ok=false
              cleanAuth(Some(u))
            }
            if(ok)
              Redirect(ADUSER(id))
            else
              Msg2("Go back and use +/- to indicate add/remove")
          }) getOrElse {
          error("ERR_ADMIN_CANT_UPDATE_USER.uname " + id + " " + errCollector.mkString)
          Unauthorized("ERR_ADMIN_CANT_UPDATE_USER.uname " + id + " " + errCollector.mkString)
        }
    })
  }

  def userUname(id: String) = FAD { implicit au => implicit errCollector => implicit request =>
    OneForm.bindFromRequest.fold(
    formWithErrors =>
      Msg2(formWithErrors.toString + "Oops, can't add that quota!"), {
      case uname =>
        (for (
          u <- Users.findUserById(id);
          pro <- u.profile;
          already <- !(u.userName == uname) orErr "Already updated"
        ) yield {
            razie.db.tx("uname", au.userName) { implicit txn =>
              Profile.updateUser(u, u.copy(userName = uname))
              Wikis.updateUserName(u.userName, uname)
              cleanAuth(Some(u))
            }
            Redirect(ADUSER(id))
          }) getOrElse {
          error("ERR_ADMIN_CANT_UPDATE_USER.uname " + id + " " + errCollector.mkString)
          Unauthorized("ERR_ADMIN_CANT_UPDATE_USER.uname " + id + " " + errCollector.mkString)
        }
    })
  }

  def updUser(id:String, f: (User, String, RazRequest) => User) = FADR {implicit stok=>
    OneForm.bindFromRequest.fold(
      formWithErrors =>
        Msg2(formWithErrors.toString + "Oops, can't !"), {
        case uname =>
          (for (
            u <- Users.findUserById(id);
            pro <- u.profile
          ) yield {
            razie.db.tx("urealms", stok.au.get.userName) { implicit txn =>
              Profile.updateUser(u, f(u, uname, stok))
              cleanAuth(Some(u))
            }
            Redirect(ADUSER(id))
          }) getOrElse {
            error("ERR_ADMIN_CANT_UPDATE_USER.urealms " + id + " " + errCollector.mkString)
            Unauthorized("ERR_ADMIN_CANT_UPDATE_USER.urealms " + id + " " + errCollector.mkString)
          }
      })
  }

  def userRealms(id: String) = {
    updUser (id, {(u, uname, stok) => u.setRealms(stok.realm, uname)})
  }

  def userRoles(id: String) = {
    updUser (id, {(u, uname, stok) => u.setRoles(stok.realm, uname)})
  }
}
