package admin

import java.util.Date
import java.util._
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
import com.avaje.ebean.annotation.Encrypted

object SendEmail extends razie.Logging {

  /** send an email
   */
  def send(to: String, from: String, subject: String, html: String) {

    val session = new Gmail(false).session

    try {
      val message = new MimeMessage(session);
      message.setFrom(new InternetAddress(from));
      message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));

      message.setSubject(subject)

      // Prepare a multipart HTML
      val multipart = new MimeMultipart();
      // Prepare the HTML
      val htmlPart = new MimeBodyPart();
      htmlPart.setContent(html, "text/html");
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
      System.out.println("Sent message successfully....");
    } catch {
      case mex: MessagingException => mex.printStackTrace();
    }
  }
}

class Gmail(val debug: Boolean = false) {

  val SMTP_HOST_NAME = "smtp.gmail.com";
  val SMTP_AUTH_USER = "support@racerkidz.com";
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

  /** SimpleAuthenticator is used to do simple authentication
   *  when the SMTP server requires it.
   */
  class SMTPAuthenticator extends javax.mail.Authenticator {
    override def getPasswordAuthentication() = {
      val username = SMTP_AUTH_USER;
      val password = Users.dec(SMTP_AUTH_PWD);
      new PasswordAuthentication(username, password);
    }
  }

}
