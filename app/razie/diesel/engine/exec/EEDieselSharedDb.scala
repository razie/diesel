/*  ____    __    ____  ____  ____,,___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine.exec

import razie.diesel.expr.ECtx
import razie.wiki.Services
import razie.wiki.model.WikiObservers

/**
  * same as memdb, but it is shared across all users, like a real micro-service would behave
  */
class EEDieselSharedDb extends EEDieselMemDbBase("diesel.db.memshared") {
  override val DB = "diesel.db.memshared"

  // todo this is dangerous - malicious users or mistakes can take out the cluster...
  // cluster support
  WikiObservers mini {
    case ev@EEDbEvent("upsert", DB, sessionId, col, id, doc) => {
      upsert(getSession(sessionId), col, id, doc, false)
    }
  }

  override def clusterize(event: EEDbEvent) = {
    Services ! event
  }

  /** use realm as session id, so shared across same realm */
  override def getSessionId(ctx: ECtx): String = {
    ctx.root.engine.flatMap(_.settings.realm).getOrElse("all")
  }

  /** don't cleanup old sessions */
  override def cleanup = {
  }

}

