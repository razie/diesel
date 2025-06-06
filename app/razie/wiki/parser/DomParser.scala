/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package razie.wiki.parser

import razie.clog
import razie.diesel.Diesel
import razie.diesel.dom.RDOM._
import razie.diesel.dom._
import razie.diesel.engine.exec.EESnakk
import razie.diesel.engine.nodes._
import razie.diesel.engine.{DomEngine, nodes}
import razie.diesel.expr._
import razie.diesel.model.DieselMsg.ENGINE
import razie.tconf.parser.{FoldingContext, LazyAstNode, LazyStaticAstNode, StaticFoldingContext, StrAstNode}
import razie.tconf.{DSpec, DUser, EPos}
import razie.wiki.Enc
import scala.Option.option2Iterable
import scala.concurrent.Future
import scala.util.Try
import scala.util.parsing.input.Positional

/** domain parser - for domain sections in a wiki */
trait DomParser extends ParserBase with ExprParser {

  // resolve an override resulting when I simplified ExprParser
  override type P = Parser[String]

  // whitespaces in java are \t\n\x0B\f\r
  override def ws = whiteSpace
  override def ows = opt(whiteSpace)

  import RDOM._

  def domainBlocks =
    aCommentLine | panno | pobject | pclass | passoc | pdef |
        pwhenTree | pwhen | pflow | pmatch | psend | pmsg | pval | pexpect | passert | pheading

  // todo this disables the caching for all specs !!! superbad as they get compiled over and over
  /** non cacheable */
  def lazyNoCacheable(k:Keyw) (f: (StrAstNode, FoldingContext[DSpec, DUser]) => StrAstNode) =
    LazyAstNode[DSpec, DUser](f).withPos(k).withKeyw(k.s)

  /** cacheable */
  def lazystatic      (f: (StrAstNode, StaticFoldingContext[DSpec]) => StrAstNode) =
    LazyStaticAstNode[DSpec](f)

  def lazystatic (k:Keyw) (f: (StrAstNode, StaticFoldingContext[DSpec]) => StrAstNode) =
    LazyStaticAstNode[DSpec](f).withPos(k).withKeyw(k.s)

  // todo replace $ with . i.e. .class

  // ----------------------

  /**
    * a comment line with //
    */
  def aCommentLine: PS = ows ~> pComment ^^ {
    case s => s
  }

  /**
    * .anno (params)
    *
    * annotation - applied to the next element. you can have just one for now
    *
    * annotations have to be in the same page and are claimed by the first element that follows
    */
  def panno: PS =
    keyw("""[.$]anno(tate)? *""".r) ~ ows ~ optAttrs ^^ {
      case k ~ _ ~ attrs => {
        lazystatic(k) { (current, ctx) =>
          ctx.we.foreach { w =>
            // accumulate annotations
            val anno = ctx.we.get.collector.getOrElse(RDomain.DOM_ANNO_LIST, Nil).asInstanceOf[List[RDOM.P]]
            w.collector.put(RDomain.DOM_ANNO_LIST, attrs ::: anno)
          }

          // collect just to indicate parsing on the left in ACE
          val a = (new Anno(attrs)).withPos(pos(k, ctx))
          collectDom(a, ctx.we)

          StrAstNode(
            s"""${a.kspan("anno", "default", pos(k, ctx))} ${mksAttrs(attrs)}
               """.stripMargin)
        }
      }
    }

  def mkPos(ctx: StaticFoldingContext[DSpec], k: Keyw) = {
    Some(EPos(ctx.we.map(_.specRef.wpath).mkString, k.pos.line, k.pos.column))
  }

  /**
    * .class X [T] (a,b:String) extends A,B {}
    */
  def pclass: PS =
    keyw("""[.$]class""".r) ~ ws ~
        qident ~ opt(ows ~> "[" ~> ows ~> repsep(qident, ",") <~ "]") ~
        optAttrs ~
        opt(ws ~> "extends" ~> ws ~> repsep(qident, ",")) ~
        opt(ws ~> "<" ~> ows ~> repsep(ident, ",") <~ ">") ~ " *".r ~
        optClassBody ^^ {
      case k ~ _ ~ name ~ tParm ~ attrs ~ ext ~ stereo ~ _ ~ funcs => {
        lazyNoCacheable(k) { (current, ctx) =>
            // no cacheable so that the inventory dynamic actions can change as
            // inventories register
            // todo - mayvbe we can optimize that?

            //consume all annotations up to here?
          val anno = ctx.we.get.collector.getOrElse(RDomain.DOM_ANNO_LIST, Nil).asInstanceOf[List[RDOM.P]]
          ctx.we.get.collector.remove(RDomain.DOM_ANNO_LIST)

          // add stereo so we know it was parsed
          var c = C(name, "", stereo.map(l=> (WikiDomain.PARSED_CAT :: l).mkString).mkString,
            ext.toList.flatten,
            tParm.map(_.mkString).mkString,
            attrs,
            funcs,
            Nil,
            anno)

          c.pos = mkPos(ctx, k)

          var actions = ""

          // was it collected? if so, merge the two defs
          ctx.we.foreach { w =>
            val rest = w.collector.getOrElse(RDomain.DOM_LIST, List[Any]()).asInstanceOf[List[AnyRef]]


            val collected = rest.collect {
              case wc: C if wc.name == c.name && (wc.parms.size > 0 || wc.methods.size > 0) => wc
            }

            if(collected.size > 0) {
              w.collector.put(RDomain.DOM_LIST, rest.filterNot(wc=> collected.exists(x=> x.eq(wc))))
            }

            // collect only if not meaningfully defined before, so you can reference a class with jsut '$class xx'
            c = collected.foldLeft(c){(a,b) => a.plus(b)}
            collectDom(c, ctx.we)

            actions = DomInventories.htmlActions(w.specRef.realm, c, None) // actions for class only, not entity
          }

          StrAstNode(
            s"""
               |<div align="right"><small>$actions </small></div>
               |<div class="well">
               |$c
               |</div>""".stripMargin)
        }
      }
    }

  /** assoc : role */
  def assRole: Parser[(String, String)] = ident ~ " *: *".r ~ ident ^^ {
    case cls ~ _ ~ role => (cls, role)
  }

  /** assoc : role */
  def justAttrs: Parser[List[PAS]] = pasattrs ^^ {
    case a => a
  }

  /** p.a.ent.met - qualified ent so at least two elements
    *
    * @return (ent, act, fullString) */
  def qualified(qcm: List[String]): (String, String, String) = {
    val ea = qcm.mkString(".") //ent+"."+ac
    val ent = qcm.dropRight(1).mkString(".")
    val act = qcm.takeRight(1).mkString //ent+"."+ac
    (ent, act, ea)
  }

  /** ent.met - qualified ent so at least two elements
    *
    * @return (ent, act, fullString) */
  def qclsMet: Parser[(String, String, String)] = (ident | "*" | jsregex) ~ "." ~ (ident | "*" | jsregex) ~ rep("." ~> (ident | "*" | jsregex)) ^^ {
    case i ~ _ ~ j ~ l => {
      qualified(i :: j :: l)
    }
  }

  /** ent.met (parms) */
  def clsMet: Parser[(String, String, List[RDOM.P])] =
    (ident | "*" | jsregex) ~
        " *. *".r ~
        (ident | "*" | jsregex) ~
        rep("." ~> (ident | "*" | jsregex)) ~
        opt(pcallattrs) ^^ {

    case i ~ _ ~ j ~ l ~ a => {
      val qcm = qualified(i :: j :: l)
      (qcm._1, qcm._2, a.toList.flatten)
    }
  }

  def jsregex: P = """/[^/]*/""".r

  /** pattern match for ent.met */
  def clsMatch: Parser[(String, String, List[RDOM.PM])] = clsMatchRegex | clsMatchEntMet

  /** simple js regex */
  def clsMatchRegex: Parser[(String, String, List[RDOM.PM])] =
    jsregex ~ optMatchAttrs ^^ {
      case m1 ~ a => {
        (m1, "", a)
      }
    }

  /** pattern match for ent.met (args) */
  def clsMatchEntMet: Parser[(String, String, List[RDOM.PM])] =
    (ident | "*" ) ~
        " *. *".r ~
        (ident | "*" ) ~
        rep("." ~> (ident | "*" )) ~
        optMatchAttrs ^^ {
    case m1 ~ _ ~ m2 ~ l ~ a => {
      val qcm = qualified(m1 :: m2 :: l)
      (qcm._1, qcm._2, a)
    }
  }

  /**
    * add a domain element to the topic
    */
  def addToDom(c: Any) = {
    lazystatic { (current, ctx) =>
      collectDom(c, ctx.we)
      StrAstNode(
        c match {
          case x: CanHtml => x.toHtml
          case s@_ =>
            //          """<span class="label label-primary">""" +
            """<p class="bg-info">""" +
              s.toString +
              """</p>"""
        }
      )
    }
  }

  /** new block-like */
  def pblock2tree (level:Int) : Parser[List[Object]] =
    ows ~>
        keyw("{") ~ optComment3 ~
        rep (aCommentLine | pif2 (level+1) | pgen2 (level+1) | pgenStep2 (level+1) /*| pgenErr*/) <~
        ows ~ "}" ^^ {

      case arrow ~ _ ~ gens => {
        EMapCls("do", "this", Nil, "=>", None, level)
            .withPosition(EPos("", arrow.pos.line, arrow.pos.column)) ::
            gens
      }
    }

  def pif2 (level:Int): Parser[List[Object]] = pif2tree (level) | pelse2tree (level)

  /** new block-like if */
  def pif2tree (level:Int) : Parser[List[Object]] =
    ows ~> keyw("""[.$]?if""".r) ~ ows ~ notoptCond ~ ows ~
        "{" ~ optComment3 ~
        rep (aCommentLine | pif2 (level+1) | pblock2tree(level + 1) | pgen2 (level+1) | pgenStep2 (level+1) /*| pgenErr*/) <~
        ows ~ "}" ^^ {

          case arrow ~ _ ~ cond ~ _ ~ _ ~ _ ~ gens => {
            val eif = EIfc(cond).withPosition(EPos("", arrow.pos.line, arrow.pos.column))
            EMapCls("do", "this", List(P.fromTypedValue("diesel.debug.level", level)), "=>", Option(eif), level)
                .withPosition(EPos("", arrow.pos.line, arrow.pos.column)) ::
                gens
          }
    }

    /** new block-like else */
  def pelse2tree (level:Int) : Parser[List[Object]] =
    ows ~> keyw("""[.$]?else""".r) ~
        ows ~ "{" ~ optComment3 ~
        rep(aCommentLine | pif2 (level+1) | pblock2tree(level + 1) | pgen2(level+1) | pgenStep2(level+1) /*| pgenErr*/) <~
        ows ~ "}" ^^ {
      case arrow ~ _ ~ _ ~ _ ~ gens => {
        val eif = EElse().withPosition(EPos("", arrow.pos.line, arrow.pos.column))
        EMapCls("do", "that", List(P.fromTypedValue("diesel.debug.level", level)), "=>", Option(eif), level)
            .withPosition(EPos("", arrow.pos.line, arrow.pos.column)) ::
            gens
      }
    }

  // if-condition or if-match
  def pif: Parser[EIf] = pifc | pifm | pelse

  // ifc is the default if
  def pifc: Parser[EIf] =
    keyw("""[.$]?ifc?""".r) ~ ows ~ notoptCond ^^ {
      case arrow ~ _ ~ b => EIfc(b.asInstanceOf[BoolExpr])
          .withPosition(EPos("", arrow.pos.line, arrow.pos.column))
    }

  // if-match
  def pifm: Parser[EIf] =
    keyw("""[.$]?match""".r) ~ ows ~ notoptMatchAttrs ^^ {
      case arrow ~ _ ~ a => EIfm(a.asInstanceOf[List[PM]])
          .withPosition(EPos("", arrow.pos.line, arrow.pos.column))
    }

  // if-else
  def pelse: Parser[EIf] =
    keyw("""[.$]?else""".r) ^^ {
      case arrow => EElse()
        .withPosition(EPos("", arrow.pos.line, arrow.pos.column))

    }

  /**
    * .match a.role (attrs)  // not used
    */
  def pmatch: PS =
    keyw("""[.$]match""".r) ~ ws ~ clsMatch ~ opt(pif) ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ cond => {
        lazystatic(k) { (current, ctx) =>
          val x = EMatch(ac, am, aa, cond)
          //          f.pos = Some(EPos(ctx.we.map(_.wid.wpath).mkString, k.pos.line, k.pos.column))
          addToDom(x).ifoldStatic(current, ctx)
        }
      }
    }

  def pArrow: Parser[String] = "=>>" | "=>" | "==>" | "<=>" ^^ {
    case s => s
  }

  /**
    * => z.role (attrs)
    */
  def pgen: Parser[EMap] =
    ows ~> opt("[|.]*".r) ~ keyw(pArrow) ~ ows ~
        opt(pif) ~ ows ~
        (clsMet | justAttrs) <~ opt(";") <~ optComment3 ^^ {
      case level ~ arrow ~ _ ~ cond ~ _ ~ cp => {
        cp match {
          // class with message
          case Tuple3(zc, zm, za) =>
            EMapCls(zc.toString, zm.toString, za.asInstanceOf[List[RDOM.P]], arrow.s, cond, level.mkString.length)
                .withPosition(EPos("", arrow.pos.line, arrow.pos.column))

          // just parm assignments
          case pas: List[_] =>
            EMapPas(pas.asInstanceOf[List[PAS]], arrow.s, cond, level.map(_.count(_ == '|')).getOrElse(0))
                .withPosition(EPos("", arrow.pos.line, arrow.pos.column))
        }

        // EPos wpath set later
      }
    }

  /**
    * just throw error, used to debug parser
    */
  def pgenErr: Parser[EMap] =
    ows ~> opt("[|.]*".r) ~ keyw(pArrow) ~ ".*".r ^^ {
      case level ~ arrow ~ cp => {
        // error
        EMapCls("diesel.parser", "error", List(P("dieselError", arrow.pos.toString)), arrow.s, None, level.mkString.length)
            .withPosition(EPos("", arrow.pos.line, arrow.pos.column))
      }
    }


  /**
    * - text - i.e. step description
    */
  def pgenStep: Parser[EMap] =
    ows ~> opt("[|.]*".r) ~ keyw("-") ~ ows ~ opt(pif) ~ ows ~ "[^\n\r;]+".r <~ opt(";") <~ optComment3 ^^ {
      case level ~ arrow ~ _ ~ cond ~ _ ~ desc => {
        val m = if (desc.trim.startsWith("todo ")) ENGINE.TODO else ENGINE.STEP
        // use expr so we can use ${xxx} inside logs
        nodes
            .EMapCls(ENGINE.ENTITY, m,
              List(
                P("desc",
                  "",
                  WTypes.wt.STRING,
                  Option(CExpr(s""""${desc}"""")))
              ), arrow.s, cond,
              level.mkString.length)
            .withPosition(EPos("", arrow.pos.line, arrow.pos.column))
      }
    }

  /**
    * - text - i.e. step description
    */
  def pgenStep2(level:Int): Parser[EMap] =
    ows ~> keyw("-") ~ ows ~ opt(pif) ~ ows ~ "[^\n\r;]+".r <~ opt(";") <~ optComment3 ^^ {
      case arrow ~ _ ~ cond ~ _ ~ desc => {
        val m = if (desc.trim.startsWith("todo ")) ENGINE.TODO else ENGINE.STEP
        // use expr so we can use ${xxx} inside logs
        nodes
            .EMapCls(ENGINE.ENTITY, m,
              List(
                P("desc",
                  "",
                  WTypes.wt.STRING,
                  Option(CExpr(s""""${desc}"""")))
              ), arrow.s, cond,
              level)
            .withPosition(EPos("", arrow.pos.line, arrow.pos.column))
      }
    }

  /**
    * - text - i.e. step description
    * todo is not working
    */
  def pgenText: Parser[EMap] =
    ows ~> keyw("[^\n\r=\\-;]".r) ~ ows ~ opt(pif) ~ ows ~ "[^\n\r;]+".r <~ opt(";") ^^ {
      case arrow ~ _ ~ cond ~ _ ~ desc => {
        val m = if (desc.trim.startsWith("todo ")) ENGINE.TODO else ENGINE.STEP
        nodes.EMapCls(ENGINE.ENTITY, m, List(P("desc", arrow.s + desc)), "-", cond).withPosition(
          EPos("", arrow.pos.line, arrow.pos.column))
      }
    }

  /**
    * this one used for fiddles, see FiddleParser
    *
    * this is not parsed in the context of a wiki, it's just for a fiddle display...
    */
  def fiddleBlocks (wpath:String) = linemsg(wpath) | linemock(wpath)
  def noFiddleB  = not("""[.$]mock""" | "[.$]send")
  def fiddleLines (wpath:String) =
    rep(fiddleBlocks(wpath) | (fiddleBlocks(wpath) ~ CRLF2) | ( (noFiddleB | "") ~ ".*".r ~ (CRLF1 | CRLF3 | CRLF2))) ^^ {
    case l => l
  }

  /**
    * this one used for fiddles, see FiddleParser
    *
    * this is not parsed in the context of a wiki, it's just for a fiddle display...
    *
    * .mock a.role (attrs) => z.role (attrs)
    */
  def linemock (wpath: String) =
    keyw("""[.$]mock""".r) ~ ws ~ optArch ~ clsMatch ~ ws ~ opt(pif) ~ rep(pgen | pgenStep) ^^ {
      case k ~ _ ~ oarch ~ Tuple3(ac, am, aa) ~ _ ~ cond ~ gen => {
        val x = nodes.EMatch(ac, am, aa, cond)
        val f = EMock(ERule(x, "mock", gen))
        f.pos = Some(EPos(wpath, k.pos.line, k.pos.column))
        f.rule.pos = Some(EPos(wpath, k.pos.line, k.pos.column))
        f
      }
    }

  /**
    * .when <tags> a.role (attrs) => z.role (attrs)
    * tags are optional and could be rule, mock, model, impl etc
    * - rule is the default for execution
    * - mock is for mocks
    * - others like model or impl are specific
    */
  def pwhenTree: PS = pwhen2Tree (level=0)

  /**
    * new style syntax when
    *
    * .when <tags> a.role (attrs) => z.role (attrs)
    * tags are optional and could be rule, mock, model, impl etc
    * - rule is the default for execution
    * - mock is for mocks
    * - others like model or impl are specific
    */
  def pwhen2Tree (level:Int): PS =
    keyw("""[.$]when|[.$]mock""".r) ~ ws ~
        optArch ~
        clsMatch ~ ws ~
        opt(pif) ~ ows ~ "{" ~ optComment3 ~
        rep (aCommentLine | pif2 (level) | pblock2tree(level + 1) | pgen2(level) | pgenStep2 (level) /*| pgenErr*/) <~
        ows ~ "}" ^^ {
      case k ~ _ ~ oarch ~ Tuple3(ac, am, aa) ~ _ ~ cond ~ _ ~ _ ~ _ ~ gens => {
        lazystatic(k) { (current, ctx) =>
          val x = nodes.EMatch(ac, am, aa, cond)
          val wpath = ctx.we.map(_.specRef.wpath).mkString
          val arch = oarch.filter(_.length > 0).getOrElse(k.s.replaceAllLiterally("2", "")) // archetype

          def collect (gens:List[_]) : List[List[Any]] = gens.collect {
            case m: EMap => List(m)
            case l: List[_] => collect(l).flatten
          }

          val flatGens = collect(gens).flatten.asInstanceOf[List[EMap]]

          val r = ERule(x, arch,
            flatGens.map { (m:EMap) =>
              m.withPosition(m.pos.get.copy(wpath = wpath))
            })

          // get the row of the last good generation compiled
          val last = flatGens
              .filter(_.isInstanceOf[HasPosition])
              .lastOption
              .map(_.asInstanceOf[HasPosition])
              .flatMap(_.pos.map(_.line))
              .getOrElse(-1)
          r.pos = Option(EPos(wpath, k.pos.line, k.pos.column, last))
          val f = if (k.s contains "when") r else EMock(r)
          addToDom(f).ifoldStatic(current, ctx)
        }
      }
    }

  /**
    *  z.role (attrs)
    */
  def pgen2 (level:Int) : Parser[EMap] =
    ows ~> opt(keyw(pArrow)) ~ ows ~
        opt(pif) ~ ows ~
        keywo(clsMet | justAttrs) <~ opt(";") <~ optComment3 ^^ {
      case xarrow ~ _ ~ cond ~ _ ~ cp => {
        val arrow = xarrow.getOrElse(Keyw("=>").setPos(cp.pos))
        cp.o match {
          // class with message
          case Tuple3(zc, zm, za) =>
            EMapCls(
              zc.toString,
              zm.toString,
              za.asInstanceOf[List[RDOM.P]],
              arrow.s,
              cond,
              level)
                .withPosition(EPos("", arrow.pos.line, arrow.pos.column))

          // just parm assignments
          case pas: List[_] =>
            EMapPas(
              pas.asInstanceOf[List[PAS]],
              arrow.s,
              cond,
              level)
                .withPosition(EPos("", arrow.pos.line, arrow.pos.column))
        }

        // EPos wpath set later
      }
    }

  /** old syntax style rule
    *
    * .when <tags> a.role (attrs) => z.role (attrs)
    * tags are optional and could be rule, mock, model, impl etc
    * - rule is the default for execution
    * - mock is for mocks
    * - others like model or impl are specific
    */
  def pwhen: PS =
    keyw("""[.$]when|[.$]mock""".r) ~ ws ~
        optArch ~
        clsMatch ~ ws ~
        opt(pif) ~ optComment3 ~
        rep(aCommentLine | pgen | pgenStep /*| pgenErr*/) ^^ {
      case k ~ _ ~ oarch ~ Tuple3(ac, am, aa) ~ _ ~ cond ~ _ ~ gens => {
        lazystatic(k) { (current, ctx) =>
          val x = nodes.EMatch(ac, am, aa, cond)
          val wpath = ctx.we.map(_.specRef.wpath).mkString
          val arch = oarch.filter(_.length > 0).getOrElse(k.s) // archetype

          //consume all annotations up to here?
          val anno = ctx.we.get.collector.getOrElse(RDomain.DOM_ANNO_LIST, Nil).asInstanceOf[List[RDOM.P]]
          ctx.we.get.collector.remove(RDomain.DOM_ANNO_LIST)

          val r = ERule(x, arch,
            gens.collect {
              case m: EMap => m
            }.map(m => m.withPosition(m.pos.get.copy(wpath = wpath))),
            anno
          )

          // get the row of the last good generation compiled
          val last = gens
              .filter(_.isInstanceOf[HasPosition])
              .lastOption
              .map(_.asInstanceOf[HasPosition])
              .flatMap(_.pos.map(_.line))
              .getOrElse(-1)
          r.pos = Option(EPos(wpath, k.pos.line, k.pos.column, last))
          val f = if (k.s contains "when") r else EMock(r)
          addToDom(f).ifoldStatic(current, ctx)
        }
      }
    }

  /**
    * .flow e.a => expr
    */
  def pflow: PS =
    keyw("""[.$]flow""".r) ~ ws ~ clsMatch ~ ws ~ opt(pif) ~ " *=>".r ~ ows ~ flowexpr ^^ {
      case k ~ _ ~ Tuple3(ac, am, aa) ~ _ ~ cond ~ _ ~ _ ~ ex => {
        lazystatic(k) { (current, ctx) =>
          val x = nodes.EMatch(ac, am, aa, cond)
          val f = EFlow(x, ex)
          f.pos = Some(EPos(ctx.we.map(_.specRef.wpath).mkString, k.pos.line, k.pos.column))
          addToDom(f).ifoldStatic(current, ctx)
        }
      }
    }

  /**
    * .assoc name a:role -> z:role
    */
  def passoc: PS =
    keyw("""[.$]assoc""".r) ~ ws ~ opt(ident <~ ws) ~ assRole ~ " *-> *".r ~ assRole ~ optAttrs ^^ {
      case k ~ _ ~ n ~ Tuple2(a, arole) ~ _ ~ Tuple2(z, zrole) ~ p => {
        lazystatic(k) { (current, ctx) =>
          val c = A(n.mkString, a, z, arole, zrole, p).withPos(pos(k, ctx))
          collectDom(c, ctx.we)
          StrAstNode(
            """<span class="label label-default">""" +
              c.toString +
              """</span>""")
        }
      }
    }

  // what is the second ident - the class? examples?
  // $object ha.haha.ha sdfsdfsdf (asdfasdfasdf)
  def pobject: PS =
    keyw("""[.$]object """.r) ~ ident ~ " *".r ~ ident ~ optAttrs ^^ {
      case k ~ name ~ _ ~ cls ~ l => {
        val o = O(name, cls, l)
        lazystatic(k) { (current, ctx) =>
          collectDom(o, ctx.we)
          StrAstNode(
            """<div class="well">""" +
              s"object $name (" + l.mkString(", ") +
              ")" +
              """</div>""")
        }
      }
    }

  /**
    * optional MATCH attributes
    */
  private def optMatchAttrs: Parser[List[RDOM.PM]] = opt(" *\\(".r ~> ows ~> repsep(pmatchattr, ows ~> "," ~ ows) <~ ows <~ ")") ^^ {
    case Some(a) => a
    case None => List.empty
  }

  /**
    * non-optional MATCH attributes
    */
  private def notoptMatchAttrs: Parser[List[RDOM.PM]] = " *\\(".r ~> ows ~> repsep(pmatchattr, ows ~> "," ~ ows <~ ows <~ ")") ^^ {
    case a => a
  }

  /**
    * condition expression not optional
    */
  private def notoptCond: Parser[BoolExpr] = " *\\(".r ~> ows ~> cond <~ ows <~ ")" ^^ {
    case x => x
  }

  val comOperators = "==|~=|~path|!=|\\?=|>=|<=|>|<|containsNot|contains|is|notIn|in|not in|not".r

  def OPS1: Parser[String] = comOperators

  /**
    * either a match or an expression (conditional)
    */
  def pmatchattr: Parser[RDOM.PM] = pmatchattrM | pmatchattrE

  /**
    * name:type[kind] OPerator xx
    * means it's a list
    *
    * pmatch is more than just a simple conditional expression
    */
  def pmatchattrM: Parser[RDOM.PM] = ows ~>
      ( (aidentaccess | aident ) <~
      not( ows ~ ("(" | not(comOperators | ")" | ":" | "\\?".r | ","))) ) ~
      // don't match ident if func call and only if followed by known symbols for valid matches
      // ident) ident,PM ident:Type etc
      // important beause otherwise we want to match pmatchattrE
      optType ~
      opt(" *\\?(?!=) *".r) ~  // negative lookahead to not match optional with value
      opt(ows ~> OPS1 ~ ows ~ expr) ^^ {

    // not(x) is neg lookahead
    // not(not(x)) is pos lookahead

    case ident ~ t ~ o ~ e => {
      var optional = o.mkString.trim
      var ttype = ""
      var dflt = ""
      val exp = e match {
        case Some("?=" ~ _ ~ v) => {
          optional = "?"
          if(v.isInstanceOf[CExpr[_]]) ttype = v.asInstanceOf[CExpr[_]].ttype
          ("?=", Some(v))
        }
        case Some(op ~ _ ~ v) => {
          if(v.isInstanceOf[CExpr[_]]) ttype = v.asInstanceOf[CExpr[_]].ttype
          (op, Some(v))
        }
        case None => ("", None)
      }
      PM(ident, t, exp._1, dflt, exp._2, optional)
    }
  }

  /**
    * condition - bool expr
    */
  def pmatchattrE: Parser[RDOM.PM] = ows ~> cond ^^ {
    case cond => {
      PM(AExprIdent(""), WTypes.wt.UNKNOWN, "", "", Some(cond))
    }
  }

  /**
    * .option name:type=value
    *
    * use them to set options
    */
  def pval: PS = keyw("[.$]va[lr]".r) ~ ows ~ pattr ^^ {
    case k ~ _ ~ a => {
      lazystatic(k) { (current, ctx) =>

        val v = EVal(a).withPos(pos(k, ctx))
        v.isFinal = (k.s.endsWith("l"))
        collectDom(v, ctx.we)

        // common warning for payload assigned, can cause issues
        if (a.name == Diesel.PAYLOAD &&
            !a.expr.exists(e => e.isInstanceOf[CExprNull])
        ) {
          val v = EWarning(
            "Assigning payload in a $val can cause side effects!",
            "If you have unexpected results from /diesel/react or /diesel/wreact, this may be the culprit!"
          ).withPos(pos(k, ctx))

          collectDom(v, ctx.we)
        }
        StrAstNode(v.toHtmlFull) // expanded nice json
      }
    }
  }


  /** positionals to get positions during parsing */
  case class Keyw(s: String) extends Positional

  protected def keyw(r: Parser[String]) = positioned(r.map(s => Keyw(s)))

  protected def keyw(r: scala.util.matching.Regex) = positioned(pkeyw(r))

  private def pkeyw(r: scala.util.matching.Regex): Parser[Keyw] = r ^^ {
    case s => Keyw(s)
  }

  // more generic positional
  case class Keywo(o: Any) extends Positional
  private def keywo(r: Parser[_]) = positioned(r.map(s => Keywo(s)))

  def pos (k:Keyw, ctx:StaticFoldingContext[DSpec]) = {
    Some(EPos(ctx.we.map(_.specRef.wpath).mkString, k.pos.line, k.pos.column))
  }

  private def lastMsg(we: Option[DSpec]) = {
    we.flatMap { w =>
      val rest = w.collector.getOrElse(RDomain.DOM_LIST, List[Any]()).asInstanceOf[List[Any]]
      // important to be at the front...
      rest.find(_.isInstanceOf[EMsg]).map(_.asInstanceOf[EMsg])
    }
  }

  /**
    * .send object.func (a,b)
    *
    * An NVP is either the spec or an instance of a function call, a message, a data object... whatever...
    */
  def psend: PS = keyw("[.$](send| ) *".r) ~
      opt("<" ~> "[^>]+".r <~ "> *".r) ~
      (clsMet | justAttrs) ~
      opt(" *: *".r ~> optAttrs) <~ " *".r <~ optComment3 ^^ {
    case k ~ stype ~ cp ~ ret => {
      lazystatic(k) { (current, ctx) =>
        val f = cp match {
          // class with message
          case Tuple3(zc, zm, za) =>
            EMsg(zc.toString, zm.toString, za.asInstanceOf[List[RDOM.P]], "send", ret.toList.flatten(identity),
              stype.mkString.trim)
                .withPos(pos(k, ctx))

          // just parm assignments
          case pas:List[_] =>
            EMsgPas(pas.asInstanceOf[List[PAS]])
                .withPos(pos(k, ctx))
        }

        collectDom(f, ctx.we)

        val html = f match {
          case m:EMsg => m.toHtmlInPage
          case m:EMsgPas => m.toHtml
        }

        StrAstNode(f.kspan("send::") + html + "<br>")
      }
    }
  }

  private def optArch : Parser[Option[String]] = opt("<" ~> "[^>]+".r <~ "> *".r) ^^ {
    case s => s
  }

  /**
    * .msg object.func (a,b)
    *
    * An NVP is either the spec or an instance of a function call, a message, a data object... whatever...
    */
  def pmsg: PS = keyw("[.$]msg *".r) ~ optArch ~ qclsMet ~ optAttrs ~ opt(" *(:|=>) *".r ~> optAttrs) <~ optComment3^^ {
    case k ~ stype ~ qcm ~ attrs ~ ret => {
      lazystatic(k) { (current, ctx) =>

        val archn =
          if (stype.exists(_.length > 0)) stype.mkString.trim
          else {
            // todo snakkers need to be plugged in and insulated better
            // if no archetype specified, find a template snakker and import stype
            val t = ctx.we.flatMap(_.findSection(qcm._3))
            val sc = t.map(_.content).mkString
            if ("" != sc) Try {
              EESnakk.parseTemplate(t, sc, attrs).method
            }.getOrElse("") else ""
          }

        val anno = ctx.we.get.collector.getOrElse(RDomain.DOM_ANNO_LIST, Nil).asInstanceOf[List[RDOM.P]]
        ctx.we.get.collector.remove(RDomain.DOM_ANNO_LIST)

        val f = EMsg(qcm._1, qcm._2, attrs, "def", ret.toList.flatten(identity), archn)

        f.pos = Some(EPos(ctx.we.map(_.specRef.wpath).mkString, k.pos.line, k.pos.column))
        collectDom(f, ctx.we)
        StrAstNode(f.toHtmlInPage + "<br>")
      }
    }
  }

  /**
    * this one used for fiddles, see FiddleParser
    *
    * just for display, not in the context of a wiki
    *
    * .msg object.func (a,b) : (out)
    */
  def linemsg(wpath: String) = keyw("[.$]msg *".r | "[.$]send\\s*".r) ~ opt("<" ~> "[^>]+".r <~ "> *".r) ~ ident ~ " *\\. *".r ~ qident ~ optAttrs ~ opt(" *: *".r ~> optAttrs) ^^ {
    case k ~ stype ~ ent ~ _ ~ ac ~ attrs ~ ret => {
      val f = EMsg(ent, ac, attrs, "def", ret.toList.flatten(identity), stype.mkString.trim)
      f.pos = Some(EPos(wpath, k.pos.line, k.pos.column))
      f
    }
  }

  /**
    * .expect object.func (a,b)
    */
  def pexpect: PS = keyw("[.$]expect".r <~ ows) ~
      opt("not" <~ ws) ~
      opt(pif) ~ ows ~
      opt(qclsMet) ~ optMatchAttrs ~ " *".r ~ opt(pif) <~ " *".r <~ optComment3 ^^ {
    case k ~ not ~ pif ~ _ ~ qcm ~ attrs ~ _ ~ cond => {
      lazystatic(k) { (current, ctx) =>
        val pos = Some(EPos(ctx.we.map(_.specRef.wpath).mkString, k.pos.line, k.pos.column))
        val f = qcm.map(qcm =>
          ExpectM(not.isDefined, nodes.EMatch(qcm._1, qcm._2, attrs, cond.orElse(pif)))
              .withPos(pos)
              .withGuard(lastMsg(ctx.we).map(_.asMatch))
        ).getOrElse(
          ExpectV(not.isDefined, attrs, cond.orElse(pif))
                .withPos(pos)
                .withGuard(lastMsg(ctx.we).map(_.asMatch))
          )
        collectDom(f, ctx.we)
        StrAstNode(f.toHtml + "<br>")
      }
    }
  }

  /**
    * todo is not working
    */
  def optAssertExprs: Parser[List[BoolExpr]] = ows ~> cond <~ ows ^^ {
    case x => x :: Nil
  }

  /**
    * .assert (a,b)
    */
  def passert: PS = keyw("[.$]assert".r <~ ws) ~ opt("not" <~ ws) ~ optAssertExprs <~ " *".r <~ optComment3 ^^ {
    case k ~ not ~ exprs => {
      lazystatic(k) { (current, ctx) =>
        val pos = Some(EPos(ctx.we.map(_.specRef.wpath).mkString, k.pos.line, k.pos.column))
        val f = ExpectAssert(not.isDefined, exprs).withPos(pos)
        collectDom(f, ctx.we)
        StrAstNode(f.toHtml + "<br>")
      }
    }
  }

  /**
    * .assert (a,b)
    */
  def pheading: PS = keyw("##+.*".r) ^^ {
    case k => {
      lazystatic(k) { (current, ctx) =>
        val f = EInfo(k.s)
        // todo why not do this here? it's done in EnginePrep
//        val f = EMsg("diesel", "heading", List(P("msg", k.s)))
        collectDom(f, ctx.we)
        StrAstNode(k.s)
      }
    }
  }

  private def collectDom(x: Any, we: Option[DSpec]) = {
    we.foreach { w =>
      val rest = w.collector.getOrElse(RDomain.DOM_LIST, List[Any]()).asInstanceOf[List[Any]]
      // important to be at the front...
      w.collector.put(RDomain.DOM_LIST, x :: rest)
    }
  }

  /**
    * .def name (a,b) : String lang {{ ... }}
    */
  def pdef: PS = keyw("[.$]def *".r) ~ qident ~ optAttrs ~ optType ~
      opt(ows ~> ("js" | "scala" | "sc")) ~
      optScript ~  /* multiline body */
      optBlock ^^ /* single line body */
  {
    case k ~ name ~ attrs ~ optType ~ optLang ~ script ~ block => {
//      lazyNoCacheable { (current, ctx) =>
      lazystatic(k) { (current, ctx) =>
        // todo why is this nocache? it' doesn't run the JS, just defines it...
          // NOTEs in case there's trouble:
          // It works with NoCacheable but it's slower sometimes. IF NoCacheable, use this with fold.
          // HOWEVER, the "script" is a StrAstNode which is static - no folding... so I'm not sure what the nocache did...
//        val f = F(name, optLang.getOrElse("js"), attrs, optType, "def", script.fold(ctx).s, block)
        val f = F(name, optLang.getOrElse("js"), attrs, optType, "def", script.s, block)
        f.withPos(mkPos(ctx, k))
        collectDom(f, ctx.we)
        StrAstNode(f.toHtml)
      }
    }
  }

  /** optional script body */
  // using positive exlusive lookahead and .*? is not-greedy
  def optScript: PS = opt(" *\\{\\{ *".r ~> "(?s).*?(?=}})".r <~ "}}") ^^ {
    case Some(lines) => lines
    case None => ""
  }

  /** optional script body */
  // using positive exlusive lookahead and .*? is not-greedy
  def pblock: PS = " *\\{\\{ *".r ~> "(?s).*?(?=}})".r <~ "}}" ^^ {
    case lines => lines
      // todo return a lambda JS executable
  }

  def optClassBody: Parser[List[RDOM.F]] = {
    // todo if I remove this first CRLF2 is goes to sh*t
    opt(" *\\{ *".r ~ CRLF2 ~>
        repsep(classDefLine | classMsgLine | emptyline , CRLF2)
        <~ opt(CRLF2) ~ " *\\} *".r) ^^ {
      case Some(a) => a.collect {
        case x: RDOM.F => x
      }
      case None => List.empty
    }
  }

  /**
    * def name (a,b) : String
    */
  def classDefLine: Parser[RDOM.F] =
    keyw(" *\\$?def *".r) ~ optArch ~ ident ~ optAttrs ~ optType ~ " *".r ~ optBlock ^^ {
      case k ~ oarch ~ name ~ a ~ t ~ _ ~ b => {
        val ar = oarch.map(_.trim).filter(_.length > 0).map(x => x + ",def").getOrElse("def")
        val f = new F(name, "js", a, t, ar, "", b)
        // todo add spec
        val p = Some(EPos("", k.pos.line, k.pos.column))
        f.withPos(p)
        f
      }
    }

  /**
    * msg name.b (a,b) : String
    */
  def emptyline: Parser[String] = ws ^^ {
    case x => ""
  }

  /**
    * msg <archetypes> name.b (a,b) : String
    */
  def classMsgLine: Parser[RDOM.F] = " *\\$?(msg|when) *".r ~> optArch ~ qident ~ optAttrs ~ optType ~ " *".r ~ opt(pgen) ^^ {
    case oarch ~ name ~ a ~ t ~ _ ~ m => {
      val ar = oarch.map(_.trim).filter(_.length > 0).map(x => x + ",msg").getOrElse("msg")
      new F(name, "js", a, t, ar, "", m.toList.map(x => new ExecutableMsg(x)))
    }
  }

  def optBlock: Parser[List[Executable]] = opt(
    " *\\{".r ~> rep1sep(statement | emptyline, CRLF2) <~ " *\\} *".r) ^^ {
    case Some(a) => a.collect {
      case x: Executable => x
    }
    case None => List.empty
  }

  def statement: Parser[Executable] = svalue | scall

  def svalue: Parser[Executable] = valueDef ^^ { case p => new ExecutableValue(p) }

  // not used yet - class member val
  // todo use optType
//  def valueDef: Parser[RDOM.P] = "val *".r ~> ident ~ opt(" *: *".r ~> opt("<>") ~ ident) ~ opt(" *\\* *".r) ~ opt
//  (" *= *".r ~> value) ^^ {
  def valueDef: Parser[RDOM.P] = "val *".r ~> ident ~ optType ~ opt(" *= *".r ~> expr) ^^ {
    case name ~ t ~ e => P(name, e.mkString, t)
  }

  // not used yet - class member val
  def scall: Parser[Executable] = ows ~> ident ~ "." ~ ident ~ optAttrs ^^ {
    case cls ~ _ ~ func ~ attres =>
      new ExecutableCall(cls, func, attres)
  }

  private def trim(s: String) = s.replaceAll("\r", "").replaceAll("^\n|\n$", "") //.replaceAll("\n", "\\\\n'\n+'")

  //
  // ---------------------- flow expressions
  //

  def flowexpr: Parser[FlowExpr] = seqexpr

  def seqexpr: Parser[FlowExpr] = parexpr ~ rep(ows ~> ("+" | "-") ~ ows ~ parexpr) ^^ {
    case a ~ l =>
      SeqExpr("+", a :: l.collect {
        case op ~ _ ~ p => p
      })
  }

  def parexpr: Parser[FlowExpr] = parterm1 ~ rep(ows ~> ("|" | "||") ~ ows ~ parterm1) ^^ {
    case a ~ l =>
      SeqExpr("|", a :: l.collect {
        case op ~ _ ~ p => p
      })
  }

  def parterm1: Parser[FlowExpr] = parblock | msgterm1

  def parblock: Parser[FlowExpr] = "(" ~ ows ~> seqexpr <~ ows ~ ")" ^^ {
    case ex => BFlowExpr(ex)
  }

  def msgterm1: Parser[FlowExpr] = qident ^^ { case i => new MsgExpr(i) }

}

class ExecutableValue(p: RDOM.P) extends ExecutableSync {
  def sForm = "val " + p.toString

  def exec(ctx: Any, parms: Any*): Any = ""
}

class ExecutableCall(cls: String, func: String, args: List[P]) extends ExecutableSync {
  def sForm = s"call $cls.$func (${args.mkString})"

  def exec(ctx: Any, parms: Any*): Any = ""
}

class ExecutableMsg(m:EMap) extends ExecutableAsync {
  def sForm = m.toHtml

  override def start(ctx: Any, inEngine:Option[DomEngine]): Future[DomEngine] = ???
}

