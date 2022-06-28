/** ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.parser

import razie.diesel.engine.nodes.{EMock, EMsg}
import razie.tconf.parser.{LazyAstNode, StrAstNode}
import razie.wiki.Services
import razie.wiki.model.{WikiEntry, WikiUser}
import razie.wiki.parser.DomParser

/** domain parser - for domain sections in a wiki */
trait DomFiddleParser extends DomParser {

  def dfiddleBlocks = pdfiddle

  private def trim(s: String) = s.replaceAll("\r", "").replaceAll("^\n|\n$", "") //.replaceAll("\n", "\\\\n'\n+'")

  // {{diesel name:type args}}
  def pdfiddle: PS = "{{" ~> """dfiddle""".r ~ "[: ]+".r ~ """[^:}]*""".r ~ "[: ]*".r ~ """[^ :}]*""".r ~ optargs ~
      "}}" ~ opt(
    CRLF1 | CRLF3 | CRLF2) ~ slinesUntil("dfiddle") <~ "{{/dfiddle}}" ^^ {
    case d ~ _ ~ name ~ _ ~ ltags ~ xargs ~ _ ~ _ ~ lines =>
      var args = xargs.toMap
      val urlArgs = "&" + args.filter(_._1 != "anon").filter(_._1 != "spec").map(t => t._1 + "=" + t._2).mkString("&")

      def ARGS(url: String) = url + (if (urlArgs != "&") urlArgs else "")

      try {
        LazyAstNode[WikiEntry, WikiUser] { (current, ctx) =>

          val tags = model.Tags.apply(ltags.toLowerCase)

          // see if we recognize some actionables to create links for them
          var links = parseAll(fiddleLines(ctx.we.get.specRef.wpath), lines.s).map { l =>
            l.collect {
              case m: EMsg => m.toHref(name, "value", ARGS)
              case st: EMock =>
                st.rule.e.asMsg.withPos(st.pos).toHref(name, "value", ARGS) +
                    " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("json", name, "json", ARGS) + ") " +
                    " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("trace", name, "debug", ARGS) + ") "
//              case s@_ => "???-"+s.toString
            }.mkString("\n")
          }.getOrElse("???")

          // todo delete this is the above works
//          var links = lines.s.lines.collect {
//            case l if l.startsWith("$msg") || l.startsWith("$send") =>
//              parseAll(linemsg(ctx.we.get.specRef.wpath), l).map { st =>
//                st.toHref(name, "value", ARGS)
//              }.getOrElse("???")
//            case l if l.startsWith("$mock") =>
//              parseAll(linemock(ctx.we.get.specRef.wpath), l).map { st =>
//                st.rule.e.asMsg.withPos(st.pos).toHref(name, "value", ARGS) +
//                  " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("json", name, "json", ARGS) + ") " +
//                  " (" + st.rule.e.asMsg.withPos(st.pos).toHrefWith("trace", name, "debug", ARGS) + ") "
//              }.getOrElse("???")
//          }.mkString("\n")

          if (links == "") links = "no recognized messages"

          val specName = args.get("spec").getOrElse(name)
          val spec = ctx.we.flatMap(
            _.sections.filter(x => x.stype == "dfiddle" && x.name == specName)
                .filter(_.signature.toLowerCase startsWith "spec")
                .map(_.content)
                .headOption
          ).filter(x => tags.contains("story")).getOrElse("")

          StrAstNode(
            views.html.fiddle.inlineDomFiddle(ctx.we.get.wid, ctx.we, name, tags, spec, args, trim(lines.s), links,
              args.contains("anon") || tags.contains("anon"), ctx.au).body,
            Map("diesel.requireJs" -> "false")
          )
        }
      }
      catch {
        case t: Throwable =>
          if (Services.config.isLocalhost) throw t // debugging
          StrAstNode(s"""<font style="color:red">[[BAD FIDDLE - check syntax: ${t.toString}]]</font>""")
      }
  }
}
