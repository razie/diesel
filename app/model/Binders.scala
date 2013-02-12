package model

import play.api.mvc.PathBindable
import play.api.mvc.QueryStringBindable
import com.mongodb.casbah.Imports._
import razie.Log

/** binders for codec pah objects in play */
object Binders {
  
  implicit def widPathBindable =
    new PathBindable[model.WID] {
      def bind(key: String, value: String): Either[String, model.WID] = {Log.log ("BINDER"+value)
        WID.fromPath(Enc.fromUrl(value)).map(Right(_)).getOrElse(Left("Oh-Uh"))
      }
      def unbind(key: String, wid: model.WID): String =
        wid.wpath
    }

  implicit def widQueryStringBindable(implicit sBinder: QueryStringBindable[String]) = new QueryStringBindable[model.WID] {
    def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, model.WID]] = {
      for {
        w  <- sBinder.bind(key, params)
      } yield {
        w match {
          case Right(ww) => WID.fromPath(Enc.fromUrl(ww)).map(x=>Right(x)).getOrElse (Left("Unable to bind bounds"))
          case _ => Left("Unable to bind bounds")
        }
      }
    }

    def unbind(key: String, wid: model.WID) =
      key+"=" +Enc.toUrl(wid.wpath)
   }

  implicit def cmwidPathBindable =
    new PathBindable[model.CMDWID] {
      def bind(key: String, value: String): Either[String, model.CMDWID] = {Log.log ("BINDER"+value)
        WID.cmdfromPath(Enc.fromUrl(value)).map(Right(_)).getOrElse(Left("Oh-Uh"))
      }
      def unbind(key: String, wid: model.CMDWID): String =
        wid.wid.get.wpath
    }

  implicit def cmwidQueryStringBindable(implicit sBinder: QueryStringBindable[String]) = new QueryStringBindable[model.CMDWID] {
    def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, model.CMDWID]] = {
      for {
        w  <- sBinder.bind(key, params)
      } yield {
        w match {
          case Right(ww) => WID.cmdfromPath(Enc.fromUrl(ww)).map(x=>Right(x)).getOrElse (Left("Unable to bind bounds"))
          case _ => Left("Unable to bind bounds")
        }
      }
    }

    def unbind(key: String, wid: model.CMDWID) =
      key+"=" +Enc.toUrl(wid.wid.get.wpath)
   }

//  implicit def widQueryStringBindable(implicit sBinder: QueryStringBindable[String]) = new QueryStringBindable[model.WID] {
//    def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, model.WID]] = {
//      for {
//        cat  <- sBinder.bind(key + ".cat", params)
//        name <- sBinder.bind(key + ".name", params)
//        section <- sBinder.bind(key + ".section", params)
//      } yield {
//        val p = sBinder.bind(key + ".p", params)
//        (cat, name) match {
//          case (Right(c), Right(n)) => p match {
//            case Some(Right(pp)) => Right(WID(c, n, Some(new ObjectId(pp)), section.fold((x=>None), Some(_))))
//            case _ => Right(WID(c, n, None, section.fold((x=>None), Some(_))))
//          }
//          case _ => Left("Unable to bind bounds")
//        }
//      }
//    }
//
//    def unbind(key: String, wid: model.WID) =
//      key+".cat=" + wid.cat + "&" + 
//      key+".name=" + wid.name + "&" + 
//      key+".section=" + wid.section + "&" + 
//      wid.parent.map(pp=>key+".p=" + pp.toString).getOrElse("")
//   }

}