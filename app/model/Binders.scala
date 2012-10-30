package model

import play.api.mvc.PathBindable
import play.api.mvc.QueryStringBindable
import com.mongodb.casbah.Imports._
import razie.Log

object Binders {
  
  implicit def widPathBindable =
    new PathBindable[model.WID] {
      def bind(key: String, value: String): Either[String, model.WID] = {Log.log ("BINDER"+value)
        controllers.Wiki.widFromPath(Enc.fromUrl(value)).map(Right(_)).getOrElse(Left("Oh-Uh"))
      }
      def unbind(key: String, wid: model.WID): String =
        wid.wpath
    }

  implicit def widQueryStringBindable(implicit sBinder: QueryStringBindable[String]) = new QueryStringBindable[model.WID] {
    def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, model.WID]] = {
      for {
        cat  <- sBinder.bind(key + ".cat", params)
        name <- sBinder.bind(key + ".name", params)
        section <- sBinder.bind(key + ".section", params)
      } yield {
        val p = sBinder.bind(key + ".p", params)
        (cat, name) match {
          case (Right(c), Right(n)) => p match {
            case Some(Right(pp)) => Right(WID(c, n, Some(new ObjectId(pp)), section.fold((x=>None), Some(_))))
            case _ => Right(WID(c, n, None, section.fold((x=>None), Some(_))))
          }
          case _ => Left("Unable to bind bounds")
        }
      }
    }

    def unbind(key: String, wid: model.WID) =
      key+".cat=" + wid.cat + "&" + 
      key+".name=" + wid.name + "&" + 
      key+".section=" + wid.section + "&" + 
      wid.parent.map(pp=>key+".p=" + pp.toString).getOrElse("")
   }

}