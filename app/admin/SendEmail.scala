package admin

import java.util._
import java.util.concurrent.TimeUnit
import javax.mail._
import javax.mail.internet._

import akka.actor.{Actor, Props}
import model.WikiConfig
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.libs.Akka
import razie.clog

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** a prepared email to send - either send now, later, backup etc */
@db.RTable
case class EmailMsg(
  to: String,
  from: String,
  subject: String,
  html: String,
  isNotification: Boolean = true,
  bcc: Seq[String] = Seq.empty,
  status: String = EmailMsg.STATUS.READY,
  lastError: String = "",
  sendCount: Integer = 0,
  lastDtm: DateTime = DateTime.now(),
  _id: ObjectId = new ObjectId()) extends db.REntity[EmailMsg] {
  def shouldResend = (EmailMsg.RESEND contains status) && (sendCount < EmailMsg.MAX_RETRY_COUNT)
}

/** email statics */
object EmailMsg {
  /** statuses for email messages */
  object STATUS {
    final val READY = "ready"
    final val SENDING = "sending"
    final val OOPS = "oops"
    final val FAILED = "failed"
    final val SKIPPED = "skipped"
  }

  // resending in these states
  val RESEND = Array (STATUS.READY, STATUS.OOPS)

  // will stop trying to send after X attempts
  val MAX_RETRY_COUNT = 3
}

/**
 * a mail session that doesn't have to connect to the server if there's nothing to send...
 *
 * you get one with SendEmail.withSession and when closed, it will spawn a thread to send the emails collected during...
 */
class MailSession(implicit mailSession: Option[Session] = None) {
  var emails: scala.List[EmailMsg] = Nil // collecting messages here to be sent at end
  lazy val session = mailSession getOrElse SendEmail.mkSession(false)
}

/** the email sender - saves emails in DB and sends them asynchronously, retrying in certain cases */
object SendEmail extends razie.Logging {
  import admin.EmailMsg.STATUS

  val NO_EMAILS = true // this is set to false for normal testing - set to true for quick testing and stress/perf testing
  var NOEMAILSTESTING = false // this is set to false for normal testing - set to true for quick testing and stress/perf testing

  // should be lazy because of akka's bootstrap
  lazy val sender = Akka.system.actorOf(Props[EmailSender], name = "EmailSender")

  // set from Global, with your actual user/pass/email server combo
  var mkSession : (Boolean) => javax.mail.Session = {debug:Boolean=>
    val props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.starttls.enable", "true");
    props.put("mail.smtp.host", "smtp.gmail.com");
    props.put("mail.smtp.port", "587");

    val session = javax.mail.Session.getInstance(props,
      new admin.SMTPAuthenticator("your@email.com", "big secret"))

    session.setDebug(debug);
    session
  }

  /**
   * send an email - just added to the session
   */
  def send(to: String, from: String, subject: String, html: String, bcc: Seq[String] = Seq.empty)(implicit mailSession: MailSession) {
    val e = new EmailMsg(to, from, subject, html, false, bcc)
    mailSession.emails = e :: mailSession.emails
    e.createNoAudit
  }

  /**
   * send an email - just added to the session. note that there is no special handling of notifications for now
   */
  def notif(to: String, from: String, subject: String, html: String, bcc: Seq[String] = Seq.empty)(implicit mailSession: MailSession) {
    val e = new EmailMsg(to, from, subject, html, true, bcc)
    mailSession.emails = e :: mailSession.emails
    e.createNoAudit
  }

  /**
   * collects all emails in session and spawns thread at end of session to send them asynchornously.
   *
   * sent emails are audited as well as failures
   */
  def withSession[C](body: (MailSession) => C): C = {
    implicit val mailSession = new MailSession
    val res = body(mailSession)

    sender ! mailSession // spawn sender

    res
  }

  val STATE_OK = "ok" // all ok
  val STATE_MAXED = "maxed" // last sending resulted in too many emails - should wait a bit

  val CMD_TICK = "tick"        // it tries to resed on a TICK every now and then
  val CMD_RESEND = "resend"    // admin command sent somehow

  var curCount = 0;            // current sending queue size
  var state = STATE_OK

  /** email sender - also acts on a timer for retries
    *
    * messages: email recovered, new email, new email session, tick, resend
    */
  private class EmailSender extends Actor {
    def receive = {
      case id: ObjectId => db.ROne[EmailMsg](id).map(e => isend(e, new MailSession))

      case e: EmailMsg => isend(e, new MailSession)

      case mailSession: MailSession => {
        curCount += mailSession.emails.size
        if (state == STATE_OK)
          mailSession.emails.reverse.map(e => isend(e, mailSession)) // reuse session
        else {
          clog << "EmailSender received messages while maxed, scheduling just one"
          mailSession.emails.lastOption.foreach (e => sender ! e._id)
        }
      }

      // check for messages to retry
      case sss @ (CMD_TICK | CMD_RESEND) => {
        clog << s"EmailSender @sss state=$state"
        resend
      }
    }

    def resend = {
      db.RMany[EmailMsg]().filter(_.shouldResend).foreach(e => {
        sender ! e._id
        curCount += 1
      })
    }

    // upon start, reload ALL messages to send - whatever was not sent last time
    override def preStart(): Unit = {
      resend

      Akka.system.scheduler.schedule(
        Duration.create(0, TimeUnit.MILLISECONDS),
        Duration.create(30, TimeUnit.MINUTES),
        this.self,
        CMD_TICK)
    }

    /** send an email */
    private def isend(ie: EmailMsg, mailSession: MailSession) {
      val e = ie.copy(status = STATUS.SENDING, sendCount = ie.sendCount + 1, lastDtm = DateTime.now())
      e.updateNoAudit

      if (Services.config.hostport.startsWith("test") && NOEMAILSTESTING ||
        // don't send emails when running test mode
        Services.config.isLocalhost && (e.isNotification || NO_EMAILS)) {
        Audit.logdb("EMAIL_SENT_NOT", Seq("to:" + e.to, "from:" + e.from, "subject:" + e.subject, "body:" + e.html).mkString("\n"))
        e.copy(status = STATUS.SKIPPED, lastDtm = DateTime.now()).updateNoAudit
      } else
        try {
          val message = new MimeMessage(mailSession.session);
          message.setFrom(new InternetAddress(e.from));
          message.addRecipient(Message.RecipientType.TO, new InternetAddress(e.to));
          e.bcc.foreach { b =>
            message.addRecipient(Message.RecipientType.BCC, new InternetAddress(b));
          }

          message.setSubject(e.subject)

          val multipart = new MimeMultipart();
          val htmlPart = new MimeBodyPart();
          htmlPart.setContent(e.html, "text/html");
          htmlPart.setDisposition("inline"); //BodyPart.INLINE);

          // PREPARE THE IMAGE
          //      val imgPart = new MimeBodyPart();
          //
          //      val fileName = "public/racerkidz-small.png";
          //
          //      var classLoader = Thread.currentThread().getContextClassLoader();
          //      if (classLoader == null) {
          //        classLoader = this.getClass().getClassLoader();
          //        if (classLoader == null) {
          //          throw new IllegalStateException("IT IS NULL AGAIN!!!!");
          //        }
          //      }
          //
          //      val ds = new URLDataSource(classLoader.getResource(fileName));
          //
          //      imgPart.setDataHandler(new DataHandler(ds));
          //      imgPart.setHeader("Content-ID", "<logoimg_cid>");
          //      imgPart.setDisposition("inline"); //MimeBodyPart.INLINE);
          //      imgPart.setFileName("logomailtemplate.png");

          multipart.addBodyPart(htmlPart);
          //      multipart.addBodyPart(imgPart);
          message.setContent(multipart);

          Transport.send(message);
          Audit.logdb("EMAIL_SENT", Seq("to:" + e.to, "from:" + e.from, "subject:" + e.subject).mkString("\n"))
          e.deleteNoAudit

          if (state == STATE_MAXED)
            resend // seems ok now: reload all messages that were waiting

          state = STATE_OK // i can send messages for sure now, eh?
        } catch {
          case mex: MessagingException => {
            e.copy(status = STATUS.OOPS, lastDtm = DateTime.now(), lastError = mex.toString).update
            Audit.logdb("ERR_EMAIL",
              Seq("to:" + e.to, "from:" + e.from,
                "subject:" + e.subject, "html=" + e.html,
                "EXCEPTION = " + mex.toString()).mkString("\n"))
            if (mex.toString contains "Daily sending quota exceeded")
              state = STATE_MAXED
            error("ERR_EMAIL", mex)
          }
        } finally {
          curCount -= 1
        }
    }
  }
}

/** simple SMTP user/pass authenticator */
class SMTPAuthenticator (userName:String, password:String) extends javax.mail.Authenticator {
  override def getPasswordAuthentication() = {
    new PasswordAuthentication(userName, password);
  }
}


