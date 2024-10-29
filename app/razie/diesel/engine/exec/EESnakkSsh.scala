/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import api.{JschBase, SftpClientBridge, SshClient}
import razie.diesel.Diesel.PAYLOAD
import razie.diesel.dom.RDOM._
import razie.diesel.dom.WTypes
import razie.diesel.engine._
import razie.diesel.engine.nodes._
import razie.diesel.expr.{ECtx, SimpleExprParser}
import razie.wiki.{Enc, Sec}

/** SSH, SFTP etc */
object EESnakkSsh {

  /** allow encrypted invisible passwords - we decrypt them on the fly if starting with "dec:" */
  private def decpass (p:String) = {
    if (p.startsWith("dec:"))
      Sec.dec(p.substring(4))
    else p
  }

  /** upload sftp
    *
    * $msg snakk.sftp.upload (host, port, user, pwd, file)
    */
  def sftpUpload(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx) = {
    val host = ctx.getRequired("host")
    val port = ctx.get("port").getOrElse("22")
    val user = ctx.getRequired("user")
    val pwd = decpass(ctx.getRequired("pwd"))
    val localPath = ctx.getRequired("localPath")
    val remotePath = ctx.getRequired("remotePath")

    val res = new InfoAccumulator()
    val output = SftpClientBridge.uploadFile(localPath, remotePath, host, port, user, pwd)
    res += EVal(new P(PAYLOAD, output, WTypes.wt.JSON)) :: Nil
    res
  }
  def sftpDelete(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx) = {
    val host = ctx.getRequired("host")
    val port = ctx.get("port").getOrElse("22")
    val user = ctx.getRequired("user")
    val pwd = decpass(ctx.getRequired("pwd"))
    val fileName = ctx.getRequired("fileName")
    val remotePath = ctx.getRequired("remotePath")

    val res = new InfoAccumulator()
    val output = SftpClientBridge.deleteFile(remotePath, fileName, host, port, user, pwd)
    res += EVal(new P(PAYLOAD, output, WTypes.wt.JSON)) :: Nil
    res
  }
  def sftpDownload(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx) = {
    val host = ctx.getRequired("host")
    val port = ctx.get("port").getOrElse("22")
    val user = ctx.getRequired("user")
    val pwd = decpass(ctx.getRequired("pwd"))
    val remotePath = ctx.getRequired("remotePath")

    val res = new InfoAccumulator()
    val output = SftpClientBridge.deleteFile(remotePath, null, host, port, user, pwd)
    res += EVal(new P(PAYLOAD, output, WTypes.wt.JSON)) :: Nil
    res
  }

  def sftpList(in: EMsg, destSpec: Option[EMsg], ea:String)(implicit ctx: ECtx) = {
    val host = ctx.getRequired("host")
    val port = ctx.get("port").getOrElse("22")
    val user = ctx.getRequired("user")
    val pwd = decpass(ctx.getRequired("pwd"))
    val remotePath = ctx.getRequired("remotePath")

    val res = new InfoAccumulator()

    var output : String = ""
    if (ea == "snakk.sftp.list") {
      output = SftpClientBridge.listFiles(remotePath, host, port, user, pwd)
    } else if (ea == "snakk.sftp.listDir") {
      output = SftpClientBridge.listDir(remotePath, host, port, user, pwd)
    } else {
      throw new IllegalArgumentException("Unknown action: " + in.ea)
    }

    res += EVal(new P(PAYLOAD, output, WTypes.wt.JSON)) :: Nil
    res
  }

  def ssh(in: EMsg, destSpec: Option[EMsg], chan:String)(implicit ctx: ECtx) = {
    val host = ctx.getRequired("host")
    val port = ctx.get("port").getOrElse("22")
    val user = ctx.getRequired("user")
    val pwd = decpass(ctx.getRequired("pwd"))
    val cmd = ctx.getRequired("cmd")

    val res = new InfoAccumulator()
    val output = SshClient.execute(cmd, new JschBase.Authorization(host, user, pwd), "exec")
    res += EVal(new P(PAYLOAD, output)) :: Nil
    res
    }

  def shell(in: EMsg, destSpec: Option[EMsg], chan:String)(implicit ctx: ECtx) = {
    val host = ctx.getRequired("host")
    val port = ctx.get("port").getOrElse("22")
    val user = ctx.getRequired("user")
    val pwd = decpass(ctx.getRequired("pwd"))
    val cmd = ctx.getRequired("cmd")

    val res = new InfoAccumulator()
    val output = SshClient.execute(cmd, new JschBase.Authorization(host, user, pwd), "shell")
    res += EVal(new P(PAYLOAD, output)) :: Nil
    res
  }
}