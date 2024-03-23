/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import razie.Logging
import razie.diesel.dom.RDOM.P.{asSimpleString, asString}
import razie.diesel.dom.RDOM._
import razie.diesel.dom.{RDomain, _}
import razie.diesel.engine.exec.Executors
import razie.diesel.engine.nodes._
import razie.diesel.expr.{CExpr, ECtx}
import razie.diesel.model.{DieselMsg, DieselTarget}
import razie.tconf.parser.{BaseAstNode, LazyAstNode, LazyStaticAstNode, LeafAstNode, ListAstNode, StrAstNode, TriAstNode}
import razie.tconf.{DSpec, EPos, TagQuery}
import razie.wiki.Enc
import razie.wiki.model.{WID, WikiEntry, Wikis}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.parsing.input.Positional

/** Documentation extraction and such */
object DomDocs extends Logging {

  /** simpler representation of the elements in the WE */
  class WElem(val node: BaseAstNode, val row: Integer)

  case class WeText(override val node: BaseAstNode, override val row: Integer) extends WElem(node, row)

  case class WeHeading(override val node: BaseAstNode, override val row: Integer) extends WElem(node, row)

  case class WeRule(override val node: BaseAstNode, override val row: Integer) extends WElem(node, row)

  case class WeAnno(override val node: BaseAstNode, override val row: Integer) extends WElem(node, row)



  /** summary cache entry for a WE */
  case class DocSummary(we: WikiEntry, summary: ListBuffer[WElem], annos:List[P])

  /** caches etc for a doc collection */
  class DocCtx (val domain:RDomain) {

    // (wpath, summary)
    private val wikiAstSummary = new mutable.HashMap[String, DocSummary]

    var domAnnotations : List[P] = Nil // todo global annotations

    def annotationsFor (wpath:String) : List[P] = wikiAstSummary.get(wpath).toList.flatMap(_.annos)

    // rollup any annotation with name from wiki or then domain
    def rollupAnno (wpath:Option[String], name:String) : Option[P] =
        annotationsFor(wpath.getOrElse(""))
            .find(_.name == name)
            .orElse(
              domAnnotations
                  .find(_.name == name)
                  .orElse(
                    domAnnotations.find(_.name == "dom." + name)
                  )
            )

    // todo evaluate in a specific context, if expr uses $val defined in what context?
    def findAnno(annos: List[P], wpath: Option[String], name: String): Option[PValue[_]] =
      annos
          .find(a => a.name == name)
          .orElse(
            rollupAnno(wpath, name)
          )
          .map (_.calculatedTypedValue(ECtx.empty))

    /** summarize Wiki AST into simpler logical nodes
      *
      * todo ideally we'd cache this when used?
      *
      * @param we
      * @return list of high level elements
      */
    def summarizeWikiAstInt (we: WikiEntry): DocSummary = {
      val sum = new ListBuffer[WElem]()

      val root = we.ast._1.asInstanceOf[ListAstNode]
      val states = root.states

      // all root nodes
      states.foreach {

        // simple text node
        case TriAstNode(_, mid: StrAstNode, _) =>

          if (isHeading(mid.s)) sum.append(WeHeading(mid, posneg(mid)))
          else sum.append(WeText(mid, posneg(mid)))

        // if list then likely not keyword, but complex string
        case TriAstNode(_, mid: ListAstNode, _) =>
          if (mid.asInstanceOf[ListAstNode].states.size > 0)
          // todo should fold, to get formatted and evaluations
            sum.append(WeText(mid, posneg(mid)))

        // rule
        case TriAstNode(_, mid: LazyStaticAstNode[_], _) =>

          getNode(mid).foreach(sum.append(_))

        // rule
        case TriAstNode(_, mid: LazyAstNode[_, _], _) =>

          getNode(mid).foreach(sum.append(_))

        // list of text node - this is a complex paragraph with text and elements
        case tan: TriAstNode =>
          sum.append(WeText(StrAstNode("eh???" + tan.mid.getClass.getSimpleName), posneg(tan.mid)))
//      if (isHeading(tan.mid.s)) sum.append(WeHeading(tan.mid, posneg(tan.mid)))
//        else sum.append (WeText(tan.mid, posneg(tan.mid)))

        case _ => ;
      }

      val collected = we.collector.getOrElse(RDomain.DOM_LIST, List[Any]()).asInstanceOf[List[Any]]
      val annos = collected.collect {
        case a:Anno => a.p
      }.toList.flatten

//      new DocSummary(we, sum, sum.filter(_.isInstanceOf[WeAnno]).map(_.asInstanceOf[WeAnno]).toList)
      new DocSummary(we, sum, annos)
    }

    /** summarize Wiki AST into simpler logical nodes
      *
      * todo ideally we'd cache this when used?
      *
      * @param we
      * @return list of high level elements
      */
    def summarizeWikiAst (we: WikiEntry): DocSummary = {
      if(!wikiAstSummary.contains(we.wid.wpath)) {
        wikiAstSummary.put(we.wid.wpath, summarizeWikiAstInt(we))
      }

      wikiAstSummary(we.wid.wpath)
    }

    /** get proper syntax node */
    def getNode(baseAstNode: BaseAstNode): Option[WElem] = baseAstNode match {

      case n: LazyStaticAstNode[_] =>

        if (n.keyw.exists(_.startsWith("$anno")))
          Some(WeAnno(n, posneg(n)))
        else
          Some(WeRule(n, posneg(n)))

      case n: LazyAstNode[_, _] => // this is just for $class really

        Some(WeRule(n, posneg(n)))

      case _ => None
    }


    def isHeading(ast: BaseAstNode) = ast.s.contains("\n#")

    def key(p: BaseAstNode) = p.keyw.getOrElse("")

    def posneg(p: BaseAstNode) = p.pos.map(_.pos.line).getOrElse(-1)

    /** @return (summary, details, annotations) */
    def docsForElementAt(row: Integer, we: WikiEntry): (String, String, List[WeAnno]) = {
      val elems = summarizeWikiAst(we).summary
      val annos = new ListBuffer[WeAnno]

      // find line node
      var idx = elems.indexWhere(_.row == row)

      // get previous paragraph
      val lines = new ListBuffer[WElem]()

      // skip annotations
      while (idx > 0 && elems.apply(idx - 1).isInstanceOf[WeAnno]) {
        //lines.prepend(elems.apply(idx-1))
        annos.prepend(elems.apply(idx-1).asInstanceOf[WeAnno])
        idx = idx - 1
      }

      // collect paragraphs backwards until another heading or rule
      while (idx > 0 && elems.apply(idx - 1).isInstanceOf[WeText]) {
        lines.prepend(elems.apply(idx - 1))
        idx = idx - 1
      }

      // todo should fold
      val para = lines.map(_.node.s).map("<p>" + _ + "</p>").mkString

      ("", para, annos.toList)
    }

    /** create a summary of all relevant entities in teh domain: messages and attributes */
    def summarizeRest(nameFilter: String = "") = {
      val msgs = new ListBuffer[RestApiCall]
      val attrs = new ListBuffer[PCol]

      domain.moreElements.collect({
        case n: ERule
          if n.e.ea == DieselMsg.ENGINE.DIESEL_REST &&
              (
                  nameFilter.isEmpty || n.pos.exists(_.wpath.endsWith(nameFilter))
                  )
          && !n.annotations.exists(a => a.name == "docs.hide" && a.currentStringValue == "true")
        => {

          restToCol(msgs, n, n.e.asMsg.withPos(n.pos))
//        n.i.foreach {
//          case e: EMapCls => msgToCol(msgs, e.asMsg.withPos(n.pos))
//          case e: EMapPas => ;
//        }
        }

        case n: ERule
          if n.e.ea == DieselMsg.ENGINE.DIESEL_REST &&
              (
                  nameFilter.isEmpty || n.pos.exists(_.wpath.endsWith(nameFilter))
                  )
              && n.annotations.exists(a => a.name == "docs.hide")
        => {

          restToCol(msgs, n, n.e.asMsg.withPos(n.pos))
//        n.i.foreach {
//          case e: EMapCls => msgToCol(msgs, e.asMsg.withPos(n.pos))
//          case e: EMapPas => ;
//        }
        }

//      case n: EMock if n.rule.e.ea == DieselMsg.ENGINE.DIESEL_REST => {
//        msgToCol(msgs, n.rule.e.asMsg.withPos(n.pos), n.rule.i.collect{
//          case e:EMapCls => e
//        }.flatMap(_.attrs))
//      }
//      case n: ExpectM => msgToCol(msgs, n.m.asMsg.withPos(n.pos))
      })

      msgs
    }

    /** one diesel.rest api to swagger */
    def restToCol (msgs: ListBuffer[RestApiCall], rule: ERule, n: EMsg, out: List[P] = Nil): Unit = {
      // todo can't protect against duplicates, can I?
      val path = rule.e.attrs.find(_.name == "path").flatMap(_.expr).filter(x => x.isInstanceOf[CExpr[_]]).map(
        _.asInstanceOf[CExpr[String]].expr)
      val verb = rule.e.attrs.find(_.name == "verb").flatMap(_.expr).filter(x => x.isInstanceOf[CExpr[_]]).map(
        _.asInstanceOf[CExpr[String]].expr).getOrElse("GET")

      // only if we find a 'path' expression
      path.foreach { p =>

        val in = new ListBuffer[PCol]() // ++= n.attrs.map(p=>PCol(p, n.pos))
        val rest = p.replaceFirst("\\?.*", "").split("/")

        // make swaggerpath with {}
        val swaggerPath = rest.collect {
          case s: String if s.startsWith(":") || s.startsWith("*") => {
            val x = s.substring(1)
            in.append(new PCol(new P(x, "", WTypes.wt.STRING), rule.pos))
            "{" + x + "}"
          }
          case r@_ => r
        }.mkString("/")

        val spec = rule.pos.flatMap { pos =>
          domain.specs.find(_.specRef.wpath == pos.wpath).map(_.asInstanceOf[WikiEntry])
              .orElse {
                WID.fromPath(pos.wpath).flatMap(_.find)
              }
        }

        // get the docs
        val docs = rule.pos.flatMap { pos =>
          spec map { we =>
            val c = we.ast
            docsForElementAt(pos.line, we)
          }
        }

        msgs.append(new RestApiCall(
          this,
          spec,
          n,
          n.pos,
          in,
          new ListBuffer[PCol](),
          new ListBuffer[PMCol],
          swaggerPath,
          verb,
          docs.map(_._1).mkString,
          docs.map(_._2).mkString,
          rule.annotations //docs.toList.flatMap(_._3)
        ))
      }
    }

    // todo collapse defs rather than select
    def msgToCol (msgs: ListBuffer[RestApiCall], n: EMsg, out: List[P] = Nil): Unit = {
      if (!msgs.exists(x => x.e == n.entity && x.a == n.met)) {
        msgs.append(new RestApiCall(
          this,
          None, // todo need this to get some docs
          n,
          n.pos,
          new ListBuffer[PCol]() ++= n.attrs.map(p => PCol(p, n.pos)),
          new ListBuffer[PCol]() ++= (n.ret ::: out).map(p => PCol(p, n.pos)),
          new ListBuffer[PMCol],
          n.urlPath()
        ))
      } else msgs.find(x => x.e == n.entity && x.a == n.met).foreach { m =>
        n.attrs.foreach { p =>
          if (m.in.exists(_.p.name == p.name)) {
            // todo collect types etc
          } else {
            m.in append PCol(p, n.pos)
          }
        }
      }
    }
  }

  def swaggerSpecs(realm: String) =
    DieselTarget.TQSPECS(realm, "", new TagQuery("swagger")).specs

  // to collect msg def
  case class PCol(p: P, pos: Option[EPos], where: String = "")

  case class PMCol(pm: PM, pos: Option[EPos])

  case class RestApiCall(ctx: DocCtx,
                         we:Option[WikiEntry],
                         m: EMsg,
                         pos: Option[EPos],
                         in: ListBuffer[PCol] = new ListBuffer[PCol],
                         out: ListBuffer[PCol] = new ListBuffer[PCol],
                         cons: ListBuffer[PMCol] = new ListBuffer[PMCol],
                         path: String,
                         verb: String = "GET",
                         summary: String = "",
                         description: String = "",
                         annos: List[P] = Nil
                        ) {
    val bodyParms: ListBuffer[PCol] = new ListBuffer[PCol]

    def e = m.entity

    def a = m.met

    def toHtml = EMsg(e, a).withPos(pos).toHtmlInPage // no parms

    /** get the docs for this element - pair of summary and detailed desc */
    def getDocs(realm: String): Option[(String, String)] = {
      wikiDocsFor(pos, realm)
    }

    // a message
    def swagM : Map[String, Any] = {
      Map(
        "summary" -> (this.verb + " " + m.ea),
        "description" -> this.description,
        "operationId" -> m.ea,

        //"produces": [ "application/json" ],

        "responses" -> Map(
          "200" -> Map(
            "description" -> "Success",
            "schema" -> Map(
              "$ref" -> ""
            )
          )
        ),

        "parameters" -> (this.in.toList.map(_.p).map(x =>
          swagP (ctx, this, x.name, "path", x.isRequired, x.ttype.name, format = x.ttype.name)
        ) ::: (
          this.annos.filter {x =>
            val n = x.name.replaceFirst("docs.param.", "")
          ! this.in.exists(y => y.p.name == n)
          }.map { x =>
          val n = x.name.replaceFirst("docs.param.", "")
          swagP (ctx, this, n, "query", x.isRequired, "String", format = "String")
          }
        )),

        "tags" -> List("generic")
      )
    }
  }

  /** create a summary of all relevant entities in teh domain: messages and attributes */
  def summarize(d: RDomain, nameFilter: String = "") = {
    val docs = new DocCtx(d)
    val msgs = new ListBuffer[RestApiCall]
    val attrs = new ListBuffer[PCol]

    d.moreElements.collect({
      case n: EMsg => {
        if (!msgs.exists(x => x.e == n.entity && x.a == n.met))
          docs.msgToCol(msgs, n)
      }
      case n: ERule => {
        docs.msgToCol(msgs, n.e.asMsg.withPos(n.pos))
        n.i.foreach {
          case e: EMapCls => docs.msgToCol(msgs, e.asMsg.withPos(n.pos))
          case e: EMapPas => ;
        }
      }
      case n: EMock => {
        docs.msgToCol(msgs, n.rule.e.asMsg.withPos(n.pos), n.rule.i.collect {
          case e: EMapCls => e
        }.flatMap(_.attrs))
      }
      case n: ExpectM => docs.msgToCol(msgs, n.m.asMsg.withPos(n.pos))
    })

    msgs
  }

  // a parameter
  def swagP(docs:DocCtx, m:RestApiCall, name: String, in: String, required: Boolean, ttype: String, format: String) = {
    val aa = docs.findAnno(m.annos, m.we.map(_.wid.wpath), "docs.param." + name)

    if(aa.exists(_.cType.isJson)) {
      val a = aa.get.asJson
      def vs(n:String, dflt:String) = a.get(n).map(_.toString).getOrElse(dflt)
      def vb(n:String, dflt:Boolean) = a.get(n).map(_.asInstanceOf[Boolean]).getOrElse(dflt)
      Map(
        "name" -> name,
        "in" -> vs("in", in),
        "required" -> vb("required", required),
        "type" -> vs("type", ttype),
        "format" -> vs("format", format),
        "description" -> vs("description", ""),
        "schema" -> vs("schema", "")
      )
    } else {
      val a = aa.map(_.asString).mkString
      Map(
        "name" -> name,
        "in" -> in,
        "required" -> required,
        "type" -> ttype,
        "format" -> format,
        "description" -> a
      )
    }
  }

  /** create a summary of all relevant entities in teh domain: messages and attributes */
  def summarizeSwagger(d: RDomain, rest: Boolean, title: String, nameFilter: String = "") = {
    val msgs = summarize(d).toList

    val paths = msgs.take(200).map(x =>
      (
          x.path -> Map(
            x.verb.toLowerCase -> x.swagM
          )
          )
    ).toMap

    Map(
      "swagger" -> "2.0",
      "info" -> Map(
        "title" -> title,
        "version" -> "version not set"
      ),
      "schemes" -> List("http", "https"),
      "consumes" -> List("application/json"),
      "produces" -> List("application/json"),
      "paths" -> paths
    )
  }

  // todo collapse defs rather than select

  /** create a summary of all relevant entities in teh domain: messages and attributes */
  def summarizeSwaggerRest(d: RDomain, rest: Boolean, title: String, nameFilter: String = "") = {
    val docs = new DocCtx(d)
    val msgs = docs.summarizeRest (nameFilter).toList

    val paths = msgs.take(200).map(x =>
      (
          x.path -> Map(
            x.verb.toLowerCase -> x.swagM
          )
          )
    ).toMap

    Map(
      "swagger" -> "2.0",
      "info" -> Map(
        "title" -> title,
        "version" -> "version not set"
      ),
      "schemes" -> List("http", "https"),
      "consumes" -> List("application/json"),
      "produces" -> List("application/json"),
      "paths" -> paths
    )

  }


  /** find the wiki for element */
  def wikiFor(p: Option[EPos], realm: String): Option[WikiEntry] = {
    p.flatMap(p => WID.fromPath(p.wpath, realm).flatMap(Wikis.find))
  }

  /** extract the docs preceeding the element
    *
    * this is all the docs preceeding the element, up to another element OR heading */
  def wikiDocsFor(p: Option[EPos], realm: String): Option[(String, String)] = {
    wikiFor(p, realm).flatMap { w =>
      val text = w.parsed
      val lines = text.lines.toList
      lines.headOption.map(x => (x -> text))

      // todo not that easy - there's empty lines etc
    }

  }

}
