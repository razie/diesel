/**  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.wiki.util

import razie.wiki.WikiConfig
import razie.wiki.model._

/**
 * dsl processing - entries with a props section
  *
  * @param section comma separated list of sections
 */
class DslProps (val we:Option[WikiPage], section:String, extra:Seq[(String,String)] = Seq()) {
  // can be reloaded in reload()
  // using seq so the order from the wiki is preserved in menus etc
  private var ipropSeq = extra
  private var iprops = ipropSeq.toMap[String,String]

  // todo at some point realmProps from Website to act as overrides here?
//  private var over:Map[String,String] = Map.empty

//  def withOverride (m:Map[String,String]) = {
//    this.over = m
//    this
//  }

  //todo stupid init pattern
  lazy val init = {
    we.map(reload)
  }

  def propSeq = {init; ipropSeq}
  def props = {init; iprops}

  def propDflt(s: String, dflt: String) = (props get s) getOrElse dflt

  def prop(s: String) = props get s

  def wprop(s: String) = (this prop s).flatMap(x => {
    WID.fromPath(x).map(_.defaultRealmTo(we.map(_.realm)))
  })

  /** convert property to boolean - use with OR */
  def bprop(s: String) = (this prop s).map(_.toUpperCase).map { p =>
    p == "TRUE" || p == "YES" || p == "1" || p == "ON"
  }

  override def toString = "AS seq: " + propSeq.mkString + "\nAs map: " + props.mkString

  // todo avoid reload, swap the entire class and use VAL instead of DEF in Website - stop parsing stuff all the time
  def reload(we: WikiPage): Unit = {
    ipropSeq = extra ++
        section.split(",").toSeq.flatMap(sec =>
          we.section("section", sec).toArray flatMap (ws => WikiConfig.parsep(ws.content))
        )
    iprops = ipropSeq.toMap[String, String]
  }

  def :: (other:DslProps) : DslProps =
    new DslProps (we, section, other.ipropSeq)

  /** put and overwrite a property */
  def put(name:String, value:String): Unit = {
    ipropSeq = ipropSeq ++ Seq(name -> value)
    iprops = ipropSeq.toMap[String,String]
  }

}

