package razie

import java.net.URLEncoder
import java.net.URLDecoder

object RString extends RStringBase
object RazString extends RStringBase

class RStringBase {
  case class RazString(s: String) {
    /** split the string in 2 sections with a separator */
    def split2(sep: String): (String, String) = {
      val a = s.split(sep)
      (if (a.size > 0) a(0) else "", if (a.size > 1) a(1) else "")
    }
    /** split the string in 3 sections with a separator */
    def split3(sep: String): (String, String, String) = {
      val a = s.split(sep)
      (if (a.size > 0) a(0) else "", if (a.size > 1) a(1) else "", if (a.size > 2) a(2) else "")
    }
    /** split the string in 4 sections with a separator */
    def split4(sep: String): (String, String, String, String) = {
      val a = s.split(sep)
      (if (a.size > 0) a(0) else "", if (a.size > 1) a(1) else "", if (a.size > 2) a(2) else "", if (a.size > 3) a(3) else "")
    }

    /** split the string in 3 sections with 2 separators */
    def split3(sep1: String, sep2: String): (String, String, String) = {
      val b = s.split(sep1)
      val x1 = if (b.size > 0) b(0) else ""
      val a = if (b.size > 1) b(1).split(sep2) else Array("")
      (x1, if (a.size > 0) a(0) else "", if (a.size > 1) a(1) else "")
    }

    def toUrl = URLEncoder.encode(s, "UTF8")
    def fromUrl = URLDecoder.decode(s, "UTF8")

  }
  implicit def toRS(o: String) = { RazString(o) }
}
