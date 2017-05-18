package razie.diesel.model

import razie.wiki.model.WID

/** a message string - send these to Services to have them executed
  *
  * these will be executed as a new process/engine instance
  *
  * @param msg properly formatted message string, i.e. $msg ent.ac (p1="value")
  */
case class DieselMsgString (msg:String, target:DieselTarget = DieselTarget("rk")) { }

/** a target for a message: either a specified list of config, or a realm
* @param specs list of specifications to get the rules from
* @param stories optional list of stories to execute and validate
*/
case class DieselTarget (realm:String, specs:List[WID]=Nil, stories:List[WID]=Nil) { }

/** a message intended for a target. Send to CQRS for execution, via Services */
case class DieselMsg (e:String, a:String, parms:Map[String,Any], target:DieselTarget=DieselTarget("rk")) {
  def toMsgString = DieselMsgString(
    s"$$msg $e.$a (" +
      (parms.map(t=> t._1 + "=" + (t._2 match {
        case s:String => s""" "$s" """
        case s:Int => s"$s"
        case s@_ => s"${s.toString}"
      })
      ).mkString(", ")) +
    ")", target
  )
}
