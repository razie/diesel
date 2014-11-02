package diesel.model

import diesel.controllers.SFiddles
import model._
import org.bson.types.ObjectId

/** reactor helpers */
object Reactors {
  final val CAT_REACTOR = "DRReactor"
  final val CAT_DOMAIN = "DRDomain"
  final val CAT_ELEMENT = "DRElement"
  final val CAT_DATA = "DRData"

  final val DSL_CAT = Map(
    "dsl.domain"->"DRDomain",
    "dsl.element"->"DRElement",
    "dsl.data"->"DRData"
  )

  final val KIND_WIKI="wiki"

  // known languages
  final val LANGS=Array("scala", "js", "ruby", "json", "xml")

  def listByUser (u:User) = u.ownedPages(CAT_REACTOR)
  def find (u:String, r:String) =
    Users.findUserByUsername(u).flatMap(_.ownedPages(CAT_REACTOR).find(_.page.exists(_.name == r)))

  def apply (we:WikiEntry) =
    new DReactor(we.owner.get._id, we.name, we.contentTags.getOrElse("kind", KIND_WIKI), we.uwid, Wikis.childrenOf(we.uwid).toSeq)

  def findLang (tags:Map[String,String], we:Option[WikiEntry]) = {
    def fromk (k:String) = k.replaceFirst("wiki", "").replaceAll("\\.", "") match {
      case "jsc1" => "js"
      case "jss1" => "js"
      case "scala1" => "scala"
      case "ruby1" => "ruby"
    }
    val lang = tags.getOrElse("lang", tags.get("kind").map(fromk).getOrElse("js"))
    lang
  }
}

/** a reactor wiki model, from the wiki */
class DReactor (
  userId : ObjectId,
  name:String,
  kind:String,
  wid:UWID,
  wids:Seq[UWID]
  ){
  lazy val page = wid.page
  lazy val pages = wids.flatMap(_.page.toSeq)

  /** create and load the reactor from the model */
  def load : DieselReactor = kind.replaceFirst("wiki", "").replaceAll("\\.", "") match {
    case "jsc1" => new JSSCReactor1 (this)
    case "jss1" => new JSSReactor1 (this)
    case "scala1" => new ScalaReactor1 (this)
    case "ruby1" => new RubyReactor1 (this)
  }
}

/** the actual reactor type/logic */
abstract class DieselReactor (
  reactor: DReactor
  ){
  import Reactors._

  lazy val sections:Seq[Diesel.Section] =
    reactor.page.toSeq.flatMap(_.sections).map(fromSection(_, reactor.page.get)) ++ (reactor.pages map fromPage)

  def domains = sections.filter(_.cat == CAT_DOMAIN)
  def bodies = sections.filter(_.cat == CAT_ELEMENT)
  def data (name:String) = sections.find(x=> x.cat == CAT_DATA && x.name == name)

  lazy val domain={
    val ho = domains.headOption
    val k = ho.map(_.lang) getOrElse ""
    new Diesel.Section(CAT_DOMAIN, ho.get.name, k, domains.map(_.script).mkString("\n"), ho.get.we)
  }

  def react (event:String, args:Map[String,String])(implicit au:User) = {
    val dom = domain
    val body = bodies.find(_.name == event)
    assert (body exists (_.lang == dom.lang))
    body.map(b=>SFiddles.isfiddleMap (addImports(dom.script, b.script), b.lang, Some(b.we), args)._2).mkString
  }

  def activate {}
  def start {}
  def stop {}
  def deactivate {}
  def supportedLang : String
  def addImports (dom:String, body:String) : String = dom + "\n" + body

  private def fromPage (we:WikiEntry) =
    new Diesel.Section(we.category, we.name, determineLang(we), cleanContent(we.content), we)
  private def fromSection (sec:WikiSection, we:WikiEntry) =
    new Diesel.Section(Reactors.DSL_CAT(sec.stype), sec.name, determineLang(sec, we), cleanContent(sec.content), we)
  private def cleanContent (s:String) =
    s.lines.filterNot(s=>s.startsWith(".") || s.stripMargin.isEmpty).mkString("\n")
  private def determineLang (we:WikiEntry):String =
    LANGS.find(we.tags contains _) getOrElse we.contentTags.getOrElse("lang", supportedLang)
  private def determineLang (sec:WikiSection, we:WikiEntry):String =
    LANGS.find(we.tags contains _) getOrElse we.contentTags.getOrElse("lang", supportedLang)

  override def toString = s"${this.getClass.getSimpleName}: ${reactor.toString} sections: ${sections.mkString}"
}

/** JavaScript Simple Client 1 reactor */
class JSSCReactor1 (reactor:DReactor) extends DieselReactor (reactor:DReactor) {
  override def supportedLang : String = "js"
  override def addImports (dom:String, body:String) : String = dom + "\n" + body
}

/** JavaScript Simple Server side 1 reactor */
class JSSReactor1 (reactor:DReactor) extends DieselReactor (reactor:DReactor) {
  override def supportedLang : String = "js"
  def allData = sections.filter(x=>x.cat == Reactors.CAT_DATA && (x.lang == "json" || x.lang == "js")).map(s=>if(s.name.length>0)s"var ${s.name} = ${s.script};" else s.script) mkString "\n\n"
  override def addImports (dom:String, body:String) : String = dom + "\n\n" + allData + "\n\n" + body
}

/** Scala Simple 1 reactor */
class ScalaReactor1 (reactor:DReactor) extends DieselReactor (reactor:DReactor) {
  override def supportedLang : String = "scala"
  def allData = sections.filter(x=>x.cat == Reactors.CAT_DATA && (x.lang == "scala")).map(s=>if(s.name.length>0)s"val ${s.name} = ${s.script}" else s.script) mkString "\n\n"
  override def addImports (dom:String, body:String) : String = dom + "\n\n" + allData + "\n\n" + body
}

/** Ruby Simple 1 reactor */
class RubyReactor1 (reactor:DReactor) extends DieselReactor (reactor:DReactor) {
  override def supportedLang : String = "ruby"
  def allData = sections.filter(x=>x.cat == Reactors.CAT_DATA && (x.lang == "ruby")).map(s=>if(s.name.length>0)s"val ${s.name} = ${s.script}" else s.script) mkString "\n\n"
  override def addImports (dom:String, body:String) : String = dom + "\n\n" + allData + "\n\n" + body
}

object Diesel {
  // i think name enables ultiple fragments
  class Section (val cat:String, val name:String, val lang:String, val body:String, val we:WikiEntry) {
    lazy val script1 = body
    lazy val script2 = we.sections.filter(_.stype == "code").map(_.content).mkString("\n")
    def script = if(lang contains "wiki") script2 else script1

    override def toString = s"{Section: $cat, $name, $lang}"
  }
//  case class Domain (kind:String, name:String, body:String, we:WikiEntry) extends Section(kind, name, body, we)
//  case class Body (kind:String, name:String, body:String, we:WikiEntry) extends Section(kind, name, body, we)
  class Grammar
  class DataSourcing
  class Rules
  case class Wiki (name:String)

  def findRequires (content:String) = {
    val PAT="""diesel.requires\(['"]([^)]+)['"]\)""".r
    PAT.findAllMatchIn(content).map(_.group(1)).toList
  }
  def findDomains (content:String) = {
    val PAT="""diesel.domain\(['"]([^)]+)['"]\)""".r
    PAT.findAllMatchIn(content).map(_.group(1)).toList
  }
  def findData (content:String) = {
    val PAT="""diesel.data\(['"]([^)]+)['"]\)""".r
    PAT.findAllMatchIn(content).map(_.group(1)).toList
  }

}


