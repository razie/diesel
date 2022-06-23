/**
  * ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  * )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  **/
package mod.diesel.model

import org.bson.types.ObjectId
import razie.tconf.parser.{LazyAstNode, StrAstNode}
import razie.wiki.model.{WikiEntry, WikiUser}
import razie.wiki.parser.ParserBase

/** parse dsl, fiddles and code specific fragments */
trait WikiDslParser extends ParserBase {

  def dslWikiProps = wikiPropFiddle | wikiPropJsFiddle | wikiPropDsl

  private def trim (s:String) = s.replaceAll("\r", "").replaceAll("^\n|\n$","")//.replaceAll("\n", "\\\\n'\n+'")

  def wikiPropJsFiddle: PS = "{{" ~> """jsfiddle""".r ~ opt(":" ~ rep(arg <~ opt(","))) ~ "}}" ~ opt(CRLF1 | CRLF3 | CRLF2) ~ slines <~ ("{{/jsfiddle}}" | "{{/}}") ^^ {
    case stype ~ xargs ~ _ ~ _ ~ lines =>
      var args = (if(xargs.isDefined) xargs.get._2 else List()).toMap
      val name = args.getOrElse("name", "")

      StrAstNode(
        views.html.fiddle.inlineBrowserJsFiddle("", trim(lines.s), args, None).body,
        Map("diesel.requireJs" -> "false")) // trim EOLs
  }

  def wikiPropFiddle: PS = "{{" ~> """fiddle""".r ~ "[: ]".r ~ """[^:}]*""".r ~ opt(":" ~ rep(arg <~ opt(","))) ~ "}}" ~ opt(CRLF1 | CRLF3 | CRLF2) ~ slines <~ "{{/fiddle}}" ^^ {
    case stype ~ _ ~ lang ~ xargs ~ _ ~ _ ~ lines =>
      var args = (if(xargs.isDefined) xargs.get._2 else List()).toMap
      val name = args.getOrElse("name", "")
      lazy val ss = lines.s.replaceAll("&lt;", "<").replaceAll("&gt;", ">") // bad html was escaped while parsing

      try {
        lang match {
          case "js" | "html" | "css" => {
            // TODO can't get this oneliner to work
            //          val re = """(?s)(<html>.*</html>).*(<style>.*</style>).*(<script>.*</script>).*""".r
            //          val  re(h, c, j) = lines.s
            val rehh = """(?s).*<head>(.*)</head>.*""".r
            val reh = """(?s).*<html>(.*)</html>.*""".r
            val rec = """(?s).*<style>(.*)</style>.*""".r
            val rej = """(?s).*<script>(.*)</script>.*""".r

            val hh = (if (ss contains "<head>") rehh.findFirstMatchIn(ss).get.group(1) else "").replaceAll("<script", "<scrRAZipt").replaceAll("</script", "</scrRAZipt")
            val h = if (ss contains "<html>")   reh.findFirstMatchIn(ss).get.group(1) else if (lang == "html") ss else ""
            val c = if (ss contains "<style>")  rec.findFirstMatchIn(ss).get.group(1) else if (lang == "css") ss else ""
            val j = if (ss contains "<script>") rej.findFirstMatchIn(ss).get.group(1) else if (lang == "js") ss else ""

            if (!(args contains "tab"))
              args = args + ("tab" -> lang)

            // remove empty lines from parsing

            StrAstNode(
              views.html.fiddle.inlineHtmlfiddle(name, args, (trim(hh), trim(h), trim(c), trim(j)), None).body,
              Map("diesel.requireJs" -> "false")
            )
          }
          case "javascript" =>
            StrAstNode(
              views.html.fiddle.inlineBrowserJsFiddle("", trim(ss.replaceFirst("\n", "")), args, None).body,
              Map("diesel.requireJs" -> "false")
            ) // trim EOLs
//            SState(views.html.fiddle.inlineHtmlfiddle(name, args, ("", "", "", trim("document.write(function(){ return " + ss.replaceFirst("\n", "") + "}())")), None).body)
          case "scala" =>
            StrAstNode(
              views.html.fiddle.inlineScalaFiddle(name, args, lines.s, None).body,
              Map("diesel.requireJs" -> "false")
            )
          case _ =>
            StrAstNode(
              views.html.fiddle.inlineScalaFiddle(name, args, lines.s, None).body,
              Map("diesel.requireJs" -> "false")
            )
        }
      }
      catch  {
        case t : Throwable =>
          if(razie.wiki.Services.config.isLocalhost) throw t // debugging
          StrAstNode(s"""<font style="color:red">[[BAD FIDDLE - check syntax: ${t.toString}]]</font>""")
      }
  }

  //  def dotPropCodeScala: PS = """^\.scala""".r ~ opt(CRLF1 | CRLF3 | CRLF2) ~> lines <~ """^\./""".r ~ opt("scala") ^^ {
  //    case lines => {
  //            lines.copy(s = ((lines.s split "\n") map ("    " + _)).mkString("\n"))
  //      lines.copy(s = "<pre><code>" + lines.s + "</code></pre>")
  //    }
  //  }

  def wikiPropDsl: PS = "{{" ~> opt(".") ~ """dsl\.\w*""".r ~ opt("[: ]".r ~ """[^:}]*""".r) ~ "}}" ~ opt(CRLF1 | CRLF3 | CRLF2) ~ slines <~ ("{{/" ~ """dsl\.\w*""".r ~ "}}") ^^ {
    case hidden ~ stype ~ opt ~ _ ~ _ ~ lines => {
      val name = opt.map(_._2) getOrElse ""
      val id = new ObjectId().toString

      def ffiddle(lang:String) = {
        // todo check perm for displaying the play button
        val script = lines.s.trim.replaceAll("\r", "")
        try {
          if(lang contains "js") {
            views.html.fiddle.inlineServerFiddle("js", script, None).body
          } else if(lang contains "scala") {
            views.html.fiddle.inlineServerFiddle("scala", script, None).body
          } else {
            "unknown language"
          }
        } catch  {
          case t : Throwable =>
            s"""<font style="color:red">[[BAD FIDDLE - check syntax: ${t.toString}]]</font>"""
        }
      }

      if(hidden.isDefined) StrAstNode("")
      else LazyAstNode[WikiEntry,WikiUser] { (current, ctx) =>
        // try to figure out the language from the content parsed so far
        val lang = Diesel.findLang(current.props, ctx.we)
        val fid = ffiddle(lang)
        StrAstNode(s"""<div><b><small>DSL ${stype.replaceFirst("dsl.","")}</b> ($name):</small><br>$fid}</div>""")//, Map.empty, List.empty, List(wffiddle))
      }
    }
  }
}
