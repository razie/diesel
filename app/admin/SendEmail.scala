package admin

import java.util.Date
import javax.mail._
import javax.mail.internet._
import javax.activation._
import javax.mail._
import javax.mail.internet._
import java.util._
import java.io._
import model.Enc
import model.Users
import model.EncUrl

/** a prepared email to send - either send now, later, backup etc */
case class EmailMsg(to: String, from: String, subject: String, html: String, isNotification:Boolean=true, bcc:Seq[String] = Seq.empty) {}

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

  /**
   * send an email
   */
  private def isend(e: EmailMsg, mailSession: MailSession) {

    val mysession = mailSession.session
      
    if (Config.hostport.startsWith("test") && NOEMAILSTESTING || 
        Config.isLocalhost && (e.isNotification || NO_EMAILS)) {
      Audit.logdb("EMAIL_SENT_NOT", Seq("to:" + e.to, "from:" + e.from, "subject:" + e.subject, "body:" + e.html).mkString("\n"))
    } else
    try {
      val message = new MimeMessage(mysession);
      message.setFrom(new InternetAddress(e.from));
      message.addRecipient(Message.RecipientType.TO, new InternetAddress(e.to));
      e.bcc.foreach { b=>
        message.addRecipient(Message.RecipientType.BCC, new InternetAddress(b));
      }

      message.setSubject(e.subject)

      // Prepare a multipart HTML
      val multipart = new MimeMultipart();
      // Prepare the HTML
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
      // Set the message content!
      message.setContent(multipart);

      // Send message
      Transport.send(message);
      Audit.logdb("EMAIL_SENT", Seq("to:" + e.to, "from:" + e.from, "subject:" + e.subject).mkString("\n"))
    } catch {
      case mex: MessagingException => {
        Audit.logdb("ERR_EMAIL", 
            Seq("to:" + e.to, "from:" + e.from, 
                "subject:" + e.subject, "html="+e.html, 
                "EXCEPTION = "+mex.toString()).mkString("\n"))
        error("ERR_EMAIL", mex)
      }
    }
  }

  /**
   * send an email
   */
  def send(to: String, from: String, subject: String, html: String, bcc:Seq[String] = Seq.empty)(implicit mailSession: MailSession) {
    mailSession.emails = new EmailMsg(to, from, subject, html, false, bcc) :: mailSession.emails
  }

  /**
   * send an email
   */
  def notif(to: String, from: String, subject: String, html: String, bcc:Seq[String] = Seq.empty)(implicit mailSession: MailSession) {
    mailSession.emails = new EmailMsg(to, from, subject, html, true, bcc) :: mailSession.emails
  }

  /**
   * collects all emails in session and spawns thread at end of session to send them asynchornously.
   *
   * sent emails are audited as well as failures
   */
  def withSession[C](body: (MailSession) => C): C = {
    implicit val mailSession = new MailSession
    val res = body(mailSession)

    // spawn sender 
    razie.Threads.fork {
      mailSession.emails.reverse.map(e => isend(e, mailSession))
    }

    res
  }

}

class Gmail(val debug: Boolean = false) {

  //  def withSession(f: (Session) => Unit) {
  //    implicit val session = this.session
  //    f(session)
  //  }

  val SMTP_HOST_NAME = "smtp.gmail.com";
  val SMTP_AUTH_USER = Config.SUPPORT
  val SMTP_AUTH_PWD = "zlMMCe7HLnMYOvbjYpPp6w==";

  def session = {
    //Set the host smtp address
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
