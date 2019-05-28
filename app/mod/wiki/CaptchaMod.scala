package mod.wiki

import razie.wiki.model.WikiEntry
import razie.wiki.mods.WikiMod

/** wiki mod for diesel and js fiddles */
class CaptchaMod extends WikiMod {
  def modName:String = "CaptchaMod"

  def modProps:Seq[String] = Seq("mod.captcha")

  def isInterested (we:WikiEntry) : Boolean =
//    we.tags.contains("fiddle") && we.tags.contains("js")
    we.content.contains("mod.captcha")
  // todo smarter - wiki already parsed, don't look at content, just the contentprops or smth

  /** modify a prop - during parsing, if you recognize this property, you can transform its value
    *
    * The idea is to allow you to define your own properties that you handle - i.e. a JSON formatter or
    * the on.snow modules for mod.book
    */
  override def modProp (prop:String, value:String, we:Option[WikiEntry]) : String = {

//  override def modPostHtml (we:WikiEntry, html:String) : String = {
//    html.replaceAllLiterally("mod.captcha",
    s"""
       |<script type="text/javascript">
       |  var onloadCallback = function() {
       |        grecaptcha.render('withrec', {
       |          'sitekey' : '6Ldg3R8TAAAAABUziKbTSYZJIWUYSA7cwSz6uBBn'
       |        });
       |      };
       |</script>
       |
       |<div id="withrec">
       |</div>
       |
       |<script src="https://www.google.com/recaptcha/api.js?onload=onloadCallback&render=explicit" async defer>
       |</script>
    """.stripMargin
    // todo share code with recaptcha.scala.html
//  )
  }
}
