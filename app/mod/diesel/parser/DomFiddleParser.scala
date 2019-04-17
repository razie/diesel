/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package razie.wiki.parser

import razie.diesel.dom.RDOM
import razie.tconf.parser.{LazyState, SState}
import razie.wiki.model.WikiEntry
import razie.wiki.model.WikiUser
import razie.wiki.Services

/** domain parser - for domain sections in a wiki */
trait DomFiddleParser extends DomParser {

  import RDOM._
  import WAST._

  def dfiddleBlocks = pdfiddle

  private def trim(s: String) = s.replaceAll("\r", "").replaceAll("^\n|\n$", "") //.replaceAll("\n", "\\\\n'\n+'")

  // {{diesel name:type args}}
  def pdfiddle: PS = "{{" ~> """dfiddle""".r ~ "[: ]+".r ~ """[^:}]*""".r ~ "[: ]*".r ~ """[^ :}]*""".r ~ optargs ~ "}}" ~ opt(CRLF1 | CRLF3 | CRLF2) ~ slinesUntil("dfiddle") <~ "{{/dfiddle}}" ^^ {
    case d ~ _ ~ name ~ _ ~ kind ~ xargs ~ _ ~ _ ~ lines =>
      var args = xargs.toMap
      val urlArgs = "&" + args.filter(_._1 != "anon").filter(_._1 != "spec").map(t=>t._1+"="+t._2).mkString("&")

      def ARGS(url:String) = url + (if(urlArgs != "&") urlArgs else "")

      try {
        LazyState[WikiEntry,WikiUser] { (current, ctx) =>

          // see if we recognize some actionables to create links for them
          var links = lines.s.lines.collect {
            case l if l.startsWith("$msg") || l.startsWith("$send") =>
              parseAll(linemsg(ctx.we.get.specPath.wpath), l).map { st =>
                st.toHref(name, "value", ARGS)
              }.getOrElse("???")
            case l if l.startsWith("$mock") =>
              parseAll(linemock(ctx.we.get.specPath.wpath), l).map { st =>
                st.rule.e.asMsg.withPos(st.pos).toHref(name, "value", ARGS) +
                  " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("json", name, "json", ARGS) + ") " +
                  " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("trace", name, "debug", ARGS) + ") "
              }.getOrElse("???")
          }.mkString("\n")

          if (links == "") links = "no recognized messages"

          val specName = args.get("spec").getOrElse(name)
          val spec = ctx.we.flatMap(
            _.sections.filter(x=> x.stype == "dfiddle" && x.name == specName)
              .filter(_.signature.toLowerCase startsWith "spec")
              .map(_.content)
              .headOption
          ).filter(x=> kind.toLowerCase=="story").getOrElse("")

          SState(
            views.html.fiddle.inlineDomFiddle(ctx.we.get.wid, ctx.we, name, kind, spec, args, trim(lines.s), links, args.contains("anon"), ctx.au).body,
            Map("diesel.requireJs" -> "false")
          )
        }
      }
      catch {
        case t: Throwable =>
          if (Services.config.isLocalhost) throw t // debugging
          SState(s"""<font style="color:red">[[BAD FIDDLE - check syntax: ${t.toString}]]</font>""")
      }
  }
}
