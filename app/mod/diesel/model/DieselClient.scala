package mod.diesel.model

import razie.wiki.model.WID

/** a message string - send these to Services to have them executed
  *
  * these will be executed as a new process/engine instance
  *
  * @param msg properly formatted message string, i.e. $msg ent.ac (p1="value")
  * @param specs list of specifications to get the rules from
  * @param stories optional list of stories to execute and validate
  */
case class DieselMsgString (msg:String, specs:List[WID], stories:List[WID]) { }

