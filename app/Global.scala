import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import admin.Audit

object Global extends GlobalSettings {
  override def onError(request: RequestHeader, ex: Throwable) = {
    admin.Audit.logdb("ERR_onError", List("request:"+request.toString, "ex:"+ex.toString).mkString("<br>"))
    super.onError(request, ex)
  }  
  
  override def onHandlerNotFound(request: RequestHeader): Result = {
    admin.Audit.logdb("ERR_onHandlerNotFound", List("request:"+request.toString).mkString("<br>"))
    super.onHandlerNotFound(request)
  }  
  
  override def onBadRequest(request: RequestHeader, error: String): Result = {
    admin.Audit.logdb("ERR_onBadRequest", List("request:"+request.toString, "error:"+error).mkString("<br>"))
    super.onBadRequest(request, error)
  }

}