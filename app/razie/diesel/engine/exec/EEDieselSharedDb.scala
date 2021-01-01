/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import razie.diesel.expr.ECtx

/**
  * same as memdb, but it is shared across all users, like a real micro-service would behave
  */
class EEDieselSharedDb extends EEDieselMemDbBase("diesel.db.memshared") {
  override val DB = "diesel.db.memshared"

  /** use realm as session id, so shared across same realm */
  override def getSessionId(ctx: ECtx): String = {
    ctx.root.engine.flatMap(_.settings.realm).getOrElse("all")
  }

  /** don't cleanup old sessions */
  override def cleanup = {
  }

}

