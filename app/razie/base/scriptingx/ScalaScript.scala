/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.base.scriptingx

import razie.base.ActionContext
import scala.collection.mutable.ListBuffer
//import razie.base.scripting.RazieInterpreter
import razie.base.scripting.ScriptContextImpl
import razie.{CSTimer, cdebug}
import scala.tools.{ nsc => nsc }
import scala.tools.nsc.interpreter.{IMain, IR}
import java.net.URL
import java.io.File
import java.net.URLClassLoader
import razie.base.scripting.RazScript

/**
 * will cache the environment, including the parser instance.
 * That way things defined in one script are visible to the next
 */
class ScalaScriptContext(parent: ActionContext = null) extends ScriptContextImpl(parent) with razie.Logging {

  private[this] val soon = razie.Threads.promise {
    val ppp = this mkParser err
    try {
      ppp.evalExpr[Any]("1+2") // prime the parser
    } catch {
      case t:Throwable => clog << t
    }
    ppp
  }

  lazy val parser = soon.get() // blocking call on Future


  // todo RAZ lost completion between 2.11 and 2.12 - jline disapeared

  //  lazy val comp = new nsc.interpreter.JLineCompletion(parser).completer()
  lazy val comp = nsc.interpreter.NoCompletion

  def this(parent: ActionContext, args: Any*) = {
    this(parent)
    // TODO I'm loosing the scala types. JavaAttrAccessImpl needs to become scala
    setAttr(args map (_.asInstanceOf[AnyRef]): _*)
  }

  /** content assist options */
  override def options(scr: String, pos: Int): java.util.List[String] = {
    bind(this, parser)
    val l = new java.util.ArrayList[String]()
    val newscr1 = scr.replaceFirst("^[ \t]+", "")
    val newscr2 = newscr1.trim
    val newpos = pos - (scr.length - newscr1.length)

    val output = comp.complete(newscr2, newpos)

    import scala.collection.JavaConversions._
    l.addAll(output.candidates)
    l
  }

  var lastError: String = null

  def err(s: String): Unit = { lastError = s }

  var expr: Boolean = true // I'm in expression mode versus interpret mode

  /** make a new parser/interpreter instance using the given error logger */
  def mkParser(errLogger: String => Unit) = {
    // when in managed class loaders we can't just use the javacp
    // TODO make this work for any managed classloader - it's hardcoded for sbt
    val env = {
      if (ScalaScript.getClass.getClassLoader.getResource("app.class.path") != null) {
        debug("Scripster using app.class.path and boot.class.path")
        // see http://gist.github.com/404272
        val settings = new nsc.Settings(errLogger)
        settings embeddedDefaults getClass.getClassLoader
        debug("Scripster using classpath: " + settings.classpath.value)
        debug("Scripster using boot classpath: " + settings.bootclasspath.value)
        settings
      } else {
        debug("Scripster using java classpath")
        val env = new nsc.Settings(errLogger)
        env.usejavacp.value = true
        env
      }
    }

    val p = new RazieInterpreter(env)
    p.setContextClassLoader
    p
  }

  /**
   * bind the values inside the context to the given interpreter instance
   *
   * NOTE that binding errors are ignored - there's just too many...watch your log for errors
   */
  def bind(ctx: ActionContext, p: nsc.Interpreter) {
    debug("binding scala variables")
    debug("binding " + "ctx")
    bindOne(p, "ctx", ctx.getClass.getCanonicalName, ctx)
    if (ctx.isInstanceOf[ScriptContextImpl] && ctx.asInstanceOf[ScriptContextImpl].parent != null) {
      debug("binding " + "ctx")
      bindOne(p, "parent", ctx.asInstanceOf[ScriptContextImpl].parent.getClass.getCanonicalName, ctx.asInstanceOf[ScriptContextImpl].parent)
    }

    debug("binding count: " + ctx.size())
    ctx.foreach { (name, value) =>
      if ("ctx" != name && "parent" != name) {

        var cls = value.getClass.getName

        if(value.isInstanceOf[ListBuffer[_]] ||
           value.isInstanceOf[List[_]] ||
           value.isInstanceOf[Seq[_]]
        )
          cls = cls + "[Any]"

        // this here reveals a screwed up handling of $$ class names in scala
        //        razie.Debug ("binding " + name + ":"+value.getClass.getSimpleName) // obj.toString causes a mess...
        //      p.bind(name, value.getClass.getCanonicalName, value)


          bindOne(p, name, cls, value)
      }
    }
  }

  def bindOne(p: nsc.Interpreter, name: String, boundType: String, value: Any) = {
    debug("binding " + name + ":" + boundType) // obj.toString causes a mess...
    try {
      p.bind(name, boundType, value)
    } catch {
      case e: Exception => {
        error("While binding variable: " + name + ":" + boundType, e)
      }
    }
  }
}

/**
  * will cache the environment, including the parser instance.
 * That way things defined in one script are visible to the next
 */
class SBTScalaScriptContext(parent: ActionContext = null) extends ScalaScriptContext(parent) {

  /** make a new parser/interpreter instance using the given error logger */
  override def mkParser(errLogger: String => Unit) = {
    // when in managed class loaders we can't just use the javacp
    // TODO make this work for any managed classloader - it's hardcoded for sbt
    val env = {
      val settings = new nsc.Settings(errLogger)
      // the repl uses the same thread
      settings.Yreplsync.value = true

      if(razie.wiki.Services.config.isLocalhost) {
        val myLoader = new ReplClassloader(getClass.getClassLoader)
        settings.embeddedDefaults(myLoader)
        settings.bootclasspath.append("/Users/raz/w/racerkidz/lib_managed/jars/org.scala-lang/scala-library/scala-library-2.11.8.jar")
        //todo figure out why the heck and remove this hardcoded bs

        // RAZ WAS COMMENTED OUT
        val cl = this.getClass.getClassLoader // or getClassLoader.getParent, or one more getParent...

        val urls = cl match {
            // HE HE HE started working when I collected this and parent
          case cl: java.net.URLClassLoader => cl.getURLs.toList //++
///*pre-2.4.6 */           cl.getParent.asInstanceOf[java.net.URLClassLoader].getURLs.toList
          case a => sys.error("oops: I was expecting an URLClassLoader, found a " + a.getClass)
        }
        val classpath = (urls map { _.toString })

        razie.cout << "=================CLASSPATH: " + classpath

        settings.bootclasspath.value = classpath.distinct.mkString(java.io.File.pathSeparator)
        settings.classpath.value = classpath.distinct.mkString(java.io.File.pathSeparator)
        settings.embeddedDefaults(cl) // or getClass.getClassLoader
      } else {
        // production - javacp is enough
        settings.usejavacp.value = true
        settings embeddedDefaults getClass.getClassLoader
      }

      settings
    }

    println ("mkParser")
    val p = new RazieInterpreter(env)
    p.setContextClassLoader
    p
  }

}

/**
 * optimization for wikis: no binding so that scripts in one page can't interact with scripts in another page
 */
class NoBindSbtScalaContext extends SBTScalaScriptContext(null) {
  override def bind(ctx: ActionContext, p: nsc.Interpreter) {}
}

/**
 * To run the Scala compiler programatically, we need to provide it with a
 * classpath, as we would if we invoked it from the command line. We need
 * to introspect our classloader (accounting for differences between execution
 * environments like IntelliJ, SBT, or WebStart), and find the paths to JAR
 * files on disk.
 */
final class ReplClassloader(parent: ClassLoader) extends ClassLoader(parent) with razie.Logging {
  override def getResource(name: String): URL = {
    // Rather pass `settings.usejavacp.value = true` (which doesn't work
    // under SBT) we do the same as SBT and respond to a resource request
    // by the compiler for the magic name "app.classpath", write the JAR files
    // from our classloader to a temporary file, and return that as the resource.
    if (name == "app.class.path") {
      def writeTemp(content: String): File = {
        val f = File.createTempFile("classpath", ".txt")
        //          IO.writeFile(f, content)
        val p = new java.io.PrintWriter(f)
        p.print(content)
        p.close
        f
      }
      info("Attempting to configure Scala classpath based on classloader: " + getClass.getClassLoader)
      val superResource = super.getResource(name)
      if (superResource != null) superResource // In SBT, let it do it's thing
      else getClass.getClassLoader match {
        case u: URLClassLoader =>
          // Rather pass `settings.usejavacp.value = true` (which doesn't work
          // under SBT) we do the same as SBT and respond to a resource request
          // by the compiler for the magic name "app.classpath"
          info("yay...")
          val files = u.getURLs.map(x => new java.io.File(x.toURI))
          val f = writeTemp(files.mkString(File.pathSeparator))
          f.toURI.toURL
        case _ =>
          // We're hosed here.
          info("uh-oh")
          null
      }
    } else super.getResource(name)
  }
}

/** utility builders */
object ScalaScriptContext {
  def apply(parms: Map[String, Any]) = {
    val s = new ScalaScriptContext(null)
    parms foreach (t => s.set(t._1, t._2))
    s
  }
  def apply(parms: (String, Any)*) = {
    val s = new ScalaScriptContext(null)
    parms foreach (t => s.set(t._1, t._2))
    s
  }
  def sbt(parms: Map[String, Any]) = {
    val s = new SBTScalaScriptContext(null)
    parms foreach (t => s.set(t._1, t._2))
    s
  }
}

/** some statics */
object ScalaScript {
  def apply(s: String) = new ScalaScript(s)

  /** convenience - make a new context here */
  def mkContext = new ScalaScriptContext()
}

/** an interpreted scala script */
class ScalaScript(val script: String) extends RazScript with razie.Logging {

  /** @return the statement */
  override def toString() = "scala:\n" + script

  /**
   * execute the script with the given context
   *
   * @param c the context for the script
   */
  override def eval(ctx: ActionContext): RazScript.RSResult[Any] = {
    var result: AnyRef = "";

    // specific scala contexts can cache a parser
    val sctx: Option[ScalaScriptContext] =
      if (ctx.isInstanceOf[ScalaScriptContext])
        Some(ctx.asInstanceOf[ScalaScriptContext])
      else None

    // otherwise, make a new parser - inefficient
    val p = sctx.map(_.parser) getOrElse (sctx.get mkParser println)

    try {
      sctx.get.bind(ctx, p)

      // this see http://lampsvn.epfl.ch/trac/scala/ticket/874 at the end, there was some work with jsr223

      // Now evaluate the script

      val r = p.evalExpr[Any](script)

      // TODO why was I converting to String?
      // convert to String
      //      result = if (r==null) "" else r.toString
      if (r != null) result = r.asInstanceOf[AnyRef]

      // bind new names back into context
      p.lastNames.foreach(m => ctx.set(m._1, m._2.asInstanceOf[AnyRef]))

      RazScript.RSSucc(result)
    } catch {
      case e: Exception => {
        razie.Warn("While processing script: " + this.script, e)
        val r = "ERROR: " + e.getMessage + " : " +
          (sctx.map(_.lastError) getOrElse "Unknown")
        sctx.map(_.lastError = "")
        RazScript.RSError(r)
      }
    }
  }

  /**
   * execute the script with the given context
   *
   * @param c the context for the script
   */
  def interactive(ctx: ActionContext): RazScript.RSResult[Any] = {
    val sctx: Option[ScalaScriptContext] =
      if (ctx.isInstanceOf[ScalaScriptContext])
        Some(ctx.asInstanceOf[ScalaScriptContext])
      else None

    val c = new CSTimer("run-deb", "-")
    c.snap("1")
    // otherwise, make a new parser - inefficient
    val p = sctx.map(_.parser) getOrElse (sctx.get mkParser println)

    try {
      c.snap("2")
      sctx.get.bind(ctx, p)
      c.snap("3")

      val ret = p.eval(new razie.base.scriptingx.ScalaScript(this.script))
      c.snap("4")

      // bind new names back into context
      p.lastNames.foreach(m => ctx.set(m._1, m._2.asInstanceOf[AnyRef]))
      c.snap("5")

      ret
    } catch {
      case e: Exception => {
        log("While processing script: " + this.script, e)
        throw e
      }
    }
  }

  def compile(ctx: ActionContext): RazScript.RSResult[Any] = RazScript.RSUnsupported("ScriptScala.compile() TODO ")

  override def lang = "scala"
}

/** scripting examples in ScriptScalaTest.scala */
