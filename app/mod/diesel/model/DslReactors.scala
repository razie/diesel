package mod.diesel.model

import com.mongodb.casbah.Imports._
import mod.diesel.controllers.SFiddles
import model._
import org.bson.types
import org.bson.types.ObjectId
import play.api.mvc.Request
import razie.db._
import razie.diesel.{RDomain, RDOM}
import razie.diesel.RDOM.C
import razie.wiki.model.WikiSection
import razie.wiki.model.Wikis
import razie.wiki.model.UWID
import razie.wiki.model.WikiEntry

/** a reactor wiki model, from the wiki */
class DslModule(
                 name: String,
                 kind: String,
                 wid: UWID,
                 wids: Seq[UWID]
                 ) {
  lazy val page = wid.page
  lazy val pages = wids.flatMap(_.page.toSeq)

  /** create and load the reactor from the model */
  def load: DieselReactor = kind.replaceFirst("wiki", "").replaceAll("\\.", "") match {
    case "jsc" => new JSSCReactor(this)
    case "jss" => new JSSReactor(this)
    case "scala" => new ScalaReactor(this)
    case "ruby" => new RubyReactor(this)
    case _ => new JSSReactor(this)
  }
}

/** the actual reactor type/logic */
abstract class DieselReactor(
  reactor: DslModule
  ) {

  import Diesel._

  /** all the sections describing domain elements: sections of the main page or pages of category */
  lazy val sections: Seq[Diesel.Section] =
    reactor.page.toSeq.flatMap(_.sections).filter(sec=>DSL_CAT.contains(sec.stype)).map(fromSection(_, reactor.page.get)) ++ (reactor.pages map fromPage)

  def domains = sections.filter(_.cat == CAT_DOMAIN)

  def bodies = sections.filter(_.cat == CAT_ELEMENT)

  def data(name: String) = sections.find(x => x.cat == CAT_DATA && x.name == name)

  /** aggregate all domain sections */
  lazy val domain = {
    val ho = domains.headOption
    val lang = ho.map(_.lang) getOrElse "js"
    new Diesel.Section(
      CAT_DOMAIN,
      ho.map(_.name).getOrElse("?"),
      lang,
      domains.map(_.script).mkString("\n"),
      ho.map(_.we).getOrElse(reactor.page.get)
    )
  }

  // todo limited to same language as the domain - one lang per reactor
  def react(event: String, args: Map[String, String])(implicit request: Request[_], au: User) = {
    val dom = domain
    val body = bodies.find(_.name == event)
    body.map { b =>
      assert(b.lang == dom.lang, "unknown language: "+b.lang + " domain lang is: "+dom.lang)
      SFiddles.isfiddleMap(addImports(dom.script, b.script), b.lang, Some(b.we), args)._2
    }.getOrElse("ERR - no body found for event: "+event+" in reactor: "+reactor.page.map(_.wid.wpath).mkString)
  }

  def activate {}

  def start {}

  def stop {}

  def deactivate {}

  def supportedLang: String

  def addImports(dom: String, body: String): String = dom + "\n" + body

  private def fromPage(we: WikiEntry) =
    new Diesel.Section(we.category, we.name, determineLang(we), cleanContent(we.content), we)

  private def fromSection(sec: WikiSection, we: WikiEntry) =
    new Diesel.Section(Diesel.DSL_CAT(sec.stype), sec.name, determineLang(sec, we), cleanContent(sec.content), we)

  private def cleanContent(s: String) =
    s.lines.filterNot(s => s.startsWith(".") || s.stripMargin.isEmpty).mkString("\n")

  private def determineLang(we: WikiEntry): String =
    LANGS.find(we.tags contains _) getOrElse we.contentTags.getOrElse("lang", supportedLang)

  private def determineLang(sec: WikiSection, we: WikiEntry): String =
    LANGS.find(we.tags contains _) getOrElse we.contentTags.getOrElse("lang", supportedLang)

  override def toString = s"${this.getClass.getSimpleName}: ${reactor.toString} sections: ${sections.mkString}"
}

/** JavaScript Simple Client 1 reactor */
class JSSCReactor(reactor: DslModule) extends DieselReactor(reactor: DslModule) {
  override def supportedLang: String = "js"

  override def addImports(dom: String, body: String): String = dom + "\n" + body
}

/** JavaScript Simple Server side 1 reactor */
class JSSReactor(reactor: DslModule) extends DieselReactor(reactor: DslModule) {
  override def supportedLang: String = "js"

  def allData = sections.filter(x => x.cat == Diesel.CAT_DATA && (x.lang == "json" || x.lang == "js")).map(s => if (s.name.length > 0) s"var ${s.name} = ${s.script};" else s.script) mkString "\n\n"

  override def addImports(dom: String, body: String): String = dom + "\n\n" + allData + "\n\n" + body
}

/** Scala Simple 1 reactor */
class ScalaReactor(reactor: DslModule) extends DieselReactor(reactor: DslModule) {
  override def supportedLang: String = "scala"

  def allData = sections.filter(x => x.cat == Diesel.CAT_DATA && (x.lang == "scala")).map(s => if (s.name.length > 0) s"val ${s.name} = ${s.script}" else s.script) mkString "\n\n"

  override def addImports(dom: String, body: String): String = dom + "\n\n" + allData + "\n\n" + body
}

/** Ruby Simple 1 reactor */
class RubyReactor(reactor: DslModule) extends DieselReactor(reactor: DslModule) {
  override def supportedLang: String = "ruby"

  def allData = sections.filter(x => x.cat == Diesel.CAT_DATA && (x.lang == "ruby")).map(s => if (s.name.length > 0) s"val ${s.name} = ${s.script}" else s.script) mkString "\n\n"

  override def addImports(dom: String, body: String): String = dom + "\n\n" + allData + "\n\n" + body
}

/** reactor helpers */
object Diesel {
  final val CAT_REACTOR = "Reactor"
  final val CAT_MODULE = "DslModule"
  final val CAT_DOMAIN = "DslDomain"
  final val CAT_ELEMENT = "DslElement"
  final val CAT_DATA = "DslData"

  final val DSL_CAT = Map(
    "dsl.domain" -> "DslDomain",
    "dsl.element" -> "DslElement",
    "dsl.data" -> "DslData"
  )

  final val KIND_WIKI = "wiki"

  // known languages
  final val LANGS = Array("scala", "js", "ruby", "json", "xml")

  def find(module: String, realm: String) = Wikis(realm).find(CAT_MODULE, module).orElse(
    Wikis(realm).find(CAT_REACTOR, module))

  def apply(we: WikiEntry) =
    new DslModule(we.name, we.contentTags.getOrElse("kind", KIND_WIKI), we.uwid, Wikis.childrenOf(we.uwid).toSeq)

  def findLang(tags: Map[String, String], we: Option[WikiEntry]) = {
    def fromk(k: String) = k.replaceFirst("wiki", "").replaceAll("\\.", "") match {
      case "jsc" => "js"
      case "jss" => "js"
      case "scala" => "scala"
      case "ruby" => "ruby"
    }
    val lang = tags.getOrElse("lang", tags.get("kind").map(fromk).getOrElse("js"))
    //todo add from page tags
    lang
  }

  // i think name enables ultiple fragments
  class Section(val cat: String, val name: String, val lang: String, val body: String, val we: WikiEntry) {
    lazy val script1 = body
    lazy val script2 = we.sections.filter(_.stype == "code").map(_.content).mkString("\n")

    def script = if (lang contains "wiki") script2 else script1

    override def toString = s"{Section: $cat, $name, $lang}"
  }

  class Grammar

  class DataSourcing

  class Rules

  case class Wiki(name: String)

  def findRequires(content: String) = {
    val PAT = """diesel.requires\(['"]([^)]+)['"]\)""".r
    PAT.findAllMatchIn(content).map(_.group(1)).toList
  }

  def findDomains(content: String) = {
    val PAT = """diesel.domain\(['"]([^)]+)['"]\)""".r
    PAT.findAllMatchIn(content).map(_.group(1)).toList
  }

  def findData(content: String) = {
    val PAT = """diesel.data\(['"]([^)]+)['"]\)""".r
    PAT.findAllMatchIn(content).map(_.group(1)).toList
  }

  /** make form definition to capture a new instance of a DSL category */
  def mkFormDef (realm:String, c:C, name:String, au:User) = {
    val TYPES = Array("", "string")

    val content = "<table class=\"table\">\n" +
      (if(!c.parms.exists(_.name == "name"))// && !c.parms.exists(_.name == "id"))
         s"""<tr>\n  <td> Name </td>\n  <td> {{f:name:}} </td>\n  <td></td></tr>\n"""
       else ""
      ) +
      c.parms.map{p=>
      "<tr>\n  <td>"+(
        if(TYPES contains p.ttype)
          s"""${p.name} </td>\n  <td> {{f:${p.name}:}}"""
        else if(p.ttype == "Image")
          s"""${p.name} </td>\n  <td> {{f:${p.name}:}} Image URL"""
        else
          s"""${p.name} COMPLICATED</td>\n  <td>""" + p.ttype
      )+"</td>\n  <td></td></tr>\n"
    }.mkString +
    "</table>\n\n" +
    s"""
{{.section:formData}}
{"formState":"created" }
{{/section}}
"""
    new WikiEntry(c.name, name, c.name+" - "+name, "md", content, au._id, Seq("dslObject", c.name.toLowerCase), realm)
  }

}


