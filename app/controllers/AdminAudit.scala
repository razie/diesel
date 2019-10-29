/**   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package controllers

import com.google.inject.Singleton
import com.mongodb.casbah.Imports.IntOk
import com.mongodb.casbah.MongoCursor
import com.mongodb.casbah.commons.MongoDBObject
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.mvc.Action
import razie.audit.ClearAudits
import razie.db.RazMongo

/** admin the audit tables directly
  *
  * todo admin ops validated and filtered by realm, for realm mods
  */
@Singleton
class AdminAudit extends AdminBase {
  def showAudit(msg: String) = FA { implicit request =>
    ROK.r admin { implicit stok =>
      views.html.admin.adminAudit(if (msg.length > 0) Some(msg) else None)
    }
  }

  def showDate(msg: String) = FA { implicit request =>
    ROK.r admin { implicit stok =>
      views.html.admin.adminAudit(None, Some(new DateTime(msg)))
    }
  }

  def clearaudit(id: String) = FA { implicit request =>
    ClearAudits.clearAudit(id, auth.get.id)
    Redirect(routes.AdminAudit.showAudit(""))
  }

  def sortIfCan [T <: MongoCursor] (c : T) = {
    if(RazMongo("Audit").size < 30000) {
      c.sort(MongoDBObject("when" -> -1))
    } else {
      c
    }
  }

  def clearauditSome(howMany: Int) = FA { implicit request =>
    sortIfCan(RazMongo("Audit").findAll()).take(howMany).map(_.get("_id").toString).toList.foreach(ClearAudits.clearAudit(_, auth.get.id))
    Redirect(routes.AdminAudit.showAudit(""))
  }

  def clearauditAll(msg: String) = FA { implicit request =>
    val MAX = 3000

    //filter or all
    if (msg.length > 0)
      sortIfCan(RazMongo("Audit").find(Map("msg" -> msg))).take(MAX).map(_.get("_id").toString).toList.foreach(ClearAudits.clearAudit(_, auth.get.id))
    else
      sortIfCan(RazMongo("Audit").findAll()).take(MAX).map(_.get("_id").toString).toList.foreach(ClearAudits.clearAudit(_, auth.get.id))
//    Redirect(routes.AdminAudit.showAudit("").withFragment("bottom"))
    Redirect(routes.AdminAudit.showAudit("").url + "#bottom")
  }

  def auditPurge1 = FA { implicit request =>
    val map = new scala.collection.mutable.HashMap[(String, String), Int]

    def count(t: String, s: String) = if (map.contains((s, t))) map.update((s, t), map((s, t)) + 1) else map.put((s, t), 1)

    RazMongo("AuditCleared").findAll().map(j => new DateTime(j.get("when"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("ac", _))
    RazMongo("WikiAudit").findAll().map(j => new DateTime(j.get("crDtm"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("w", _))
    RazMongo("UserEvent").findAll().map(j => new DateTime(j.get("when"))).map(d => s"${d.getYear}-${d.getMonthOfYear}").foreach(count("u", _))
    ROK.r admin { implicit stok => views.html.admin.adminAuditPurge1(map) }
  }

  final val auditCols = Map("AuditCleared" -> "when", "WikiAudit" -> "crDtm", "UserEvent" -> "when")

  def auditPurge(ym: String) = Action { implicit request =>
    forAdmin {
      val Array(y, m, what) = ym.split("-")
      val (yi, mi) = (y.toInt, m.toInt)
      Ok(RazMongo(what).findAll().filter(j => {
        val d = new DateTime(j.get(auditCols(what)));
        d.getYear() == yi && d.getMonthOfYear() == mi
      }).take(20000).toList.map { x =>
        RazMongo(what).remove(Map("_id" -> x.get("_id").asInstanceOf[ObjectId]))
        x
      }.mkString("\n"))
    }
  }

  def auditReport(d: String, what: Int) = Action { implicit request =>
    forAdmin {
      val baseline = DateTime.now.minusDays(what)

      def f(j: DateTime) = j != null && (d == "d" && j.isAfter(baseline) || d == "y" && j.dayOfYear.get == baseline.dayOfYear.get)

      val sevents = {
        val events =
          (
            (RazMongo("Audit").findAll().filter(j => f(j.get("when").asInstanceOf[DateTime])).toList
              ++
              RazMongo("AuditCleared").findAll().filter(j => f(j.get("when").asInstanceOf[DateTime])).toList).groupBy(_.get("msg"))).map { t =>
            (t._2.size, t._1)
          }.toList.sortWith(_._1 > _._1)
        events.map(_._1).sum + " Events:\n" +
          events.map(t => f"${t._1}%3d , ${t._2}").mkString("\n")
      }
      val sadds = {
        val adds =
          (
            (RazMongo("Audit").find(Map("msg" -> "ADD_SKI")).filter(j => f(j.get("when").asInstanceOf[DateTime])).toList
              ++
              RazMongo("AuditCleared").find(Map("msg" -> "ADD_SKI")).filter(j => f(j.get("when").asInstanceOf[DateTime])).toList)).map(o =>
            (o.get("msg"), o.get("details"), o.get("when"))).toList
        adds.size + " Adds:\n" +
          adds.mkString("\n")
      }
      val spages = {
        val pages =
          (
            RazMongo("WikiAudit").findAll().filter(j => f(j.get("crDtm").asInstanceOf[DateTime])).toList.groupBy(x => (x.get("event"), x.get("wpath")))).map { t =>
            (t._2.size, t._1)
          }.toList.sortWith(_._1 > _._1)
        pages.map(_._1).sum + " Pages:\n" +
          pages.map(t => f"${t._1}%3d , ${t._2._1}, ${t._2._2}").mkString("\n")
      }
      Ok(sevents +
        "\n\n=========================================\n\n" +
        sadds +
        "\n\n=========================================\n\n" +
        spages)
    }
  }
}

/** utilities for audits */
object AdminAudit {
  def auditSummary = {
    val x = RazMongo("Audit").findAll().toList
    RazMongo("Audit").findAll().toList.groupBy(_.get("msg")).map { t =>
      (t._2.size, t._1.toString)
    }.toList.sortWith(_._1 > _._1)
  }

  def dateSummary = {
    val x = RazMongo("Audit").findAll().toList
    RazMongo("Audit").findAll().toList.groupBy(_.get("when").asInstanceOf[DateTime].toLocalDate).map { t =>
       (t._2.size, t._1.toString)
     }.toList.sortWith(_._1 > _._1)
  }

  def ffilter(date:DateTime)(db: com.mongodb.casbah.Imports.DBObject) : Boolean = {
    db.get("when") match {
      case dtm:DateTime => dtm.toDate.equals(date.toDate)
      case _ => false
    }
  }
}
