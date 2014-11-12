package admin

import java.util.concurrent.TimeUnit
import javax.mail._
import javax.mail.internet._
import java.util._
import akka.actor.{ Actor, Props }
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.libs.Akka
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import razie.clog

/** a prepared email to send - either send now, later, backup etc */
case class EmailMsg(
  to: String,
  from: String,
  subject: String,
  html: String,
  isNotification: Boolean = true,
  bcc: Seq[String] = Seq.empty,
  status: String = EmailMsg.STATUS_READY,
  lastError: String = "",
  sendCount: Integer = 0,
  lastDtm: DateTime = DateTime.now(),
  _id: ObjectId = new ObjectId()) extends db.REntity[EmailMsg] {
  def shouldResend = (EmailMsg.RESEND contains status) && (sendCount < EmailMsg.MAX_RETRY_COUNT)
}

object EmailMsg {
  val STATUS_READY = "ready"
  val STATUS_SENDING = "sending"
  val STATUS_OOPS = "oops"
  val STATUS_FAILED = "failed"
  val STATUS_SKIPPED = "skipped"

  val RESEND = Array (STATUS_READY, STATUS_OOPS)

  val MAX_RETRY_COUNT = 3
}

/**
 * a mail session that doesn't have to connect to the server if there's nothing to send...
 *
 * you get one with SendEmail.withSession and when closed, it will spawn a thread to send the emails collected during...
 */
class MailSession(implicit mailSession: Option[Session] = None) {
  lazy val session = mailSession.getOrElse(new Gmail(false).session)
  var emails: scala.List[EmailMsg] = Nil
}

object SendEmail extends razie.Logging {
  val NO_EMAILS = true // this is set to false for normal testing - set to true for quick testing and stress/perf testing
  var NOEMAILSTESTING = false // this is set to false for normal testing - set to true for quick testing and stress/perf testing

  lazy val sender = Akka.system.actorOf(Props[EmailSender], name = "EmailSender")

  /**
   * send an email
   */
  def send(to: String, from: String, subject: String, html: String, bcc: Seq[String] = Seq.empty)(implicit mailSession: MailSession) {
    val e = new EmailMsg(to, from, subject, html, false, bcc)
    mailSession.emails = e :: mailSession.emails
    e.createNoAudit
  }

  /**
   * send an email
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

  val CMD_TICK = "tick"
  val CMD_RESEND = "resend"

  var curCount = 0;
  var state = STATE_OK

  class EmailSender extends Actor {
    def receive = {
      case id: ObjectId => db.ROne[EmailMsg](id).map(e => isend(e, new MailSession))
      case e: EmailMsg => isend(e, new MailSession)
      case mailSession: MailSession => {
        curCount += mailSession.emails.size
        if (state == STATE_OK)
          mailSession.emails.reverse.map(e => isend(e, mailSession))
        else {
          clog << "EmailSender received messages while maxed, scheduling just one"
          mailSession.emails.lastOption.foreach (e => sender ! e._id)
        }
      }
      case CMD_TICK => {
        // check for messages to retry
        clog << s"EmailSender CMD_TICK state=$state"
        //        if(state == STATE_MAXED)
        //         db.ROne[EmailMsg]("state" -> EmailMsg.STATUS_OOPS).foreach(e=> {self ! e._id; curCount += 1})
        db.RMany[EmailMsg]().filter(_.shouldResend).foreach(e => { self ! e._id; curCount += 1 })
      }

      case CMD_RESEND => {
        clog << s"EmailSender CMD_RESEND state=$state"
        // check for messages to retry
        db.RMany[EmailMsg]().filter(_.shouldResend).foreach(e => { self ! e._id; curCount += 1 })
      }
    }

    // reload ALL messages to send - whatever was not sent last time
    override def preStart(): Unit = {
      db.RMany[EmailMsg]().filter(_.shouldResend).foreach(e => { this.sender ! e._id; curCount += 1 })

      Akka.system.scheduler.schedule(
        Duration.create(0, TimeUnit.MILLISECONDS),
        Duration.create(30, TimeUnit.MINUTES),
        this.self,
        CMD_TICK)
    }

    /** send an email */
    private def isend(ie: EmailMsg, mailSession: MailSession) {
      val e = ie.copy(status = EmailMsg.STATUS_SENDING, sendCount = ie.sendCount + 1, lastDtm = DateTime.now())
      e.updateNoAudit

      val mysession = mailSession.session

      if (Config.hostport.startsWith("test") && NOEMAILSTESTING ||
        Config.isLocalhost && (e.isNotification || NO_EMAILS)) {
        Audit.logdb("EMAIL_SENT_NOT", Seq("to:" + e.to, "from:" + e.from, "subject:" + e.subject, "body:" + e.html).mkString("\n"))
        e.copy(status = EmailMsg.STATUS_SKIPPED, lastDtm = DateTime.now()).updateNoAudit
      } else
        try {
          val message = new MimeMessage(mysession);
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

          if (state == STATE_MAXED) {
            // reload all messages that were waiting
            db.RMany[EmailMsg]("state" -> EmailMsg.STATUS_OOPS).foreach(e => { sender ! e._id; curCount += 1 })
          }

          state = STATE_OK // reset state if it was bad
        } catch {
          case mex: MessagingException => {
            e.copy(status = EmailMsg.STATUS_OOPS, lastDtm = DateTime.now(), lastError = mex.toString).update
            Audit.logdb("ERR_EMAIL",
              Seq("to:" + e.to, "from:" + e.from,
                "subject:" + e.subject, "html=" + e.html,
                "EXCEPTION = " + mex.toString()).mkString("\n"))
            if (mex.toString contains ("Daily sending quota exceeded"))
              state = STATE_MAXED
            error("ERR_EMAIL", mex)
          }
        } finally {
          synchronized {
            curCount -= 1
          }
        }
    }
  }
}

class Gmail(val debug: Boolean = false) {
  val SMTP_HOST_NAME = "smtp.gmail.com";
  val SMTP_AUTH_USER = Config.SUPPORT
  val SMTP_AUTH_PWD = "zlMMCe7HLnMYOvbjYpPp6w==";

  lazy val session = {
    val props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp.starttls.enable", "true");
    props.put("mail.smtp.host", "smtp.gmail.com");
    props.put("mail.smtp.port", "587");

    val session = Session.getInstance(props, new SMTPAuthenticator());

    session.setDebug(debug);
    session
  }

  /**
   * SimpleAuthenticator is used to do simple authentication
   *  when the SMTP server requires it.
   */
  class SMTPAuthenticator extends javax.mail.Authenticator {
    import model.Sec._

    override def getPasswordAuthentication() = {
      val username = SMTP_AUTH_USER;
      val password = SMTP_AUTH_PWD.dec;
      new PasswordAuthentication(username, password);
    }
  }

}

