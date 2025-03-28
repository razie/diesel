/**  ____    __    ____  ____  ____,,___     ____  __  __  ____
  * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package razie.diesel.dom

import java.time.chrono.IsoChronology
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder, ResolverStyle}
import org.json.{JSONArray, JSONObject}

/** expression and data types
  *
  * note these are physical or base types.
  */
object WTypes {

  def isObject(ttype: WType) = {
    JSON == ttype.name || OBJECT == ttype.name
  }

  def schemaOf(ttype: WType) : String = {
    if(JSON == ttype.name || OBJECT == ttype.name) ttype.schema else ttype.name
  }

  /** constants for complex types */
  object wt {
    final val NUMBER=WType("Number")
    final val STRING=WType("String")
    final val DATE=WType("Date")
    final val REGEX=WType("Regex")

    final val PASSWORD=WType("Password")

    final val INT=WType("Int")
    final val FLOAT=WType("Float")
    final val BOOLEAN=WType("Boolean")

    final val REF=WType("Ref") // a generic ref

    final val RANGE=WType("Range")

    final val HTML=WType("HTML")     // why not... html templates?

    final val XML = WType("XML")
    final val JSON = WType("JSON")     // see asJson
    final val OBJECT = WType("Object") // java object - serialize via json

    final val SOURCE = WType("Source") // a source of values

    final val ARRAY = WType("Array")   // pv.asArray
    final val PRODUCER = WType("Producer") // paginated producer, see DieselProducer. Can be used instead of array with streams etc

    final val BYTES = WType("Bytes")

    final val EXCEPTION = WType("Exception")
    final val ERROR = WType("Error")

    final val UNKNOWN = WType("")

    final val URL = WType("URL")

    final val MSG = WType("Msg")   // a message (to call)
    final val FUNC = WType("Func") // a function (to call)

    final val UNDEFINED = WType("Undefined") // same as null - it means it"s missing, not that it has an empty value

    final val EMPTY = WType("")
  }

  // constants for simple types -
  // todo should deprecate

  final val NUMBER = "Number"
  final val STRING = "String"
  final val DATE = "Date"
  final val REGEX = "Regex"

  final val INT = "Int"
  final val FLOAT = "Float"
  final val BOOLEAN = "Boolean"

  final val PASSWORD = "Password"

  final val REF = "Ref"

  final val RANGE = "Range"

  final val HTML = "HTML"     // why not... html templates? msg can create html results

  final val XML = "XML"
  final val JSON = "JSON"     // see asJson
  final val json = "json"     // use the uppercase everywhere - this is for formats
  final val OBJECT = "Object" // java object - serialize via json. todo: need a serialization framework

  final val SOURCE = "Source" // a source of values

  final val ARRAY = "Array"   // pv.asArray

  final val BYTES = "Bytes"

  final val EXCEPTION = "Exception"
  final val ERROR = "Error"

  final val URL = "URL"

  final val UNKNOWN = ""

  final val DOMAIN = "Domain"   // an entire domain
  final val MSG = "Msg"   // a message (to call)
  final val FUNC = "Func" // a function (to call)
  final val CLASS = "Class"   // a class in the domain

  final val UNDEFINED = "Undefined" // same as null - it means it's missing, not that it has an empty value

  final val CONTENT_TYPE = "Content-type" // same as null - it means it's missing, not that it has an empty value

  final val DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" // std iso format
  final val DATE_ONLY_FORMAT = "yyyy-MM-dd"

  final val ISO_DATE_PARSER = new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .append(DateTimeFormatter.ISO_LOCAL_DATE)
      .appendLiteral('T')
      .append(DateTimeFormatter.ISO_LOCAL_TIME)
      .appendLiteral('Z')
      .toFormatter()

  /** just a few mime types */
  object Mime {
    final val appJson = "application/json"
    final val appText = "application/text"
    final val appXml = "application/xml"
    final val textPlain = "text/plain"
    final val textHtml = "text/html"
  }

  // see also P.fromTypedValue
  /** find the simple type of value, by type */
  def typeOf (x:Any) = {
    val t = x match {
      case m: collection.Map[_, _] => wt.JSON
      case s: String => wt.STRING
      case i: Int => wt.NUMBER
      case i: Long => wt.NUMBER
      case f: Double => wt.NUMBER
      case f: Float => wt.NUMBER
      case l: List[_] => wt.ARRAY
      case l: JSONArray => wt.ARRAY
      case l: JSONObject => wt.JSON
//      case l: EMsg => MSG
      case h @ _ => wt.UNKNOWN
    }
    t
  }

  val PRIMARY_TYPES = Array(
    NUMBER , STRING , DATE , REGEX , INT , FLOAT , BOOLEAN , REF , RANGE, ARRAY, HTML, XML, JSON,
    SOURCE, BYTES, EXCEPTION, DOMAIN, ERROR, MSG, FUNC, CLASS, URL, PASSWORD
  )

  /** get corresponding mime content-type */
  def getContentType (ttype:WType):String = {
    val t = ttype.name match {
      case JSON | ARRAY | OBJECT => Mime.appJson
      case XML => Mime.appXml
      case HTML => Mime.textHtml

      case NUMBER | STRING | DATE | REGEX | INT | FLOAT | BOOLEAN | RANGE => Mime.textPlain

      case h @ _ if h.contains("/") => h.trim // whatever came in - maybe it's valid :)
      case _ => Mime.textPlain
    }
    t
  }

  /** check known static subtype hierarchies
    *
    * todo for dynamic hierarchies, based on DOM, use the RDomain
    *
    * @param typeToCheck the type we're checking
    * @param subtype     the subtype
    */
  def isSubtypeOf (typeToCheck: WType, subtype: WType): Boolean = {
    typeToCheck.name.toLowerCase == subtype.name.toLowerCase || {
      typeToCheck.name.toLowerCase == EXCEPTION && subtype.name.toLowerCase == ERROR
    } || {
      (typeToCheck.name == JSON || typeToCheck.name == OBJECT) &&
          (subtype.name == JSON || subtype.name == OBJECT) &&
          (
              // check schemas if they're subtyped...
              subtype.schema == "" || typeToCheck.schema == subtype.name
              )
    }
  }

  /** check known static subtype hierarchies
    *
    * for dynamic hierarchies, based on DOM, use the RDomain
    *
    * @param typeToCheck the type we're checking
    * @param subtype     the subtype
    */
  def isSubtypeOf (typeToCheck: String, subtype: String): Boolean = {
    typeToCheck.trim.length > 0 && (typeToCheck.toLowerCase == subtype.toLowerCase || {
      typeToCheck.toLowerCase == EXCEPTION && subtype.toLowerCase == ERROR
      // todo check schemas if they're subtyped...
    })
  }

  /** check known static subtype hierarchies
    *
    * for dynamic hierarchies, based on DOM, use the RDomain
    */
  def isSubtypeOf (a:WType, b:String):Boolean = isSubtypeOf(a, WType(b))

  // todo deprecate when typecast not needed
//  def apply(wt:WType):WType = wt

  /** make a nice complex html type name, supports containers */
  def mkString (t:WType, classLink:String=>String):String = {
    val col = (if (t.name.isEmpty) "" else ":" + (if (t.isRef) "<>" else ""))
    var res = if (t.schema == WTypes.UNKNOWN) col + classLink(t.name)
    else if (t.name == WTypes.OBJECT || t.name == WTypes.JSON) col + t.name
    else if (WTypes.ARRAY == t.name) col + t.name
    else col + classLink(t.name)

    res = res + t.wrappedType.map("[" + classLink(_) + "]").mkString
    res = res + t.mime.map("[" + _ + "]").mkString
    res
  }

}

/** an actual type, possibly an object with a schema OR a container with a contained schema or a simple type
  *
  * For objects, the name is JSON or Object and the schema is the class name
  *
  * @param name        is the WType
  * @param schema      is either a DOMType or some indication of a schema in context, wrapped type for arrays etc
  * @param mime        is an optional precise mime to be represented in
  * @param isRef       if this is a reference versus containment
  */
case class WType (name:String, schema:String = WTypes.UNKNOWN, mime:Option[String]=None, isRef:Boolean=false) {

  /** get the wrapped type, if any type is wrapped (schema) */
  def wrappedType: Option[String] = if (hasSchema) Option(schema) else None

  /** the comparison takes into account the schema too */
  override def equals(obj: Any) = {
    obj match {
      case wt: WType => this.name == wt.name && this.schema == wt.schema
      case s: String => this.name == s
    }
  }

  /** get class name: schema if this is array or complex object or name otherwise - BEST replacement for ttype.name */
  def getClassName = if (name == WTypes.JSON || name == WTypes.OBJECT || WTypes.ARRAY == name) schema else name

  /** careful - you may want to call p.withSchema instead, which sets both P and PV schemas */
  def withSchema(s: String) = copy(schema = s)

  def hasSchema = schema != "" && schema != WTypes.UNKNOWN

  def withMime(s: Option[String]) = copy(mime = s)

  def withRef(b: Boolean) = copy(isRef = b)

  override def toString = WTypes.mkString(this, identity)

  def isEmpty = name.isEmpty

  def isUndefined = WTypes.UNDEFINED.equals(name)

  def isNumber = WTypes.NUMBER == name || WTypes.FLOAT == name || WTypes.INT == name
  def isArray = WTypes.ARRAY == name
  def isJson = WTypes.JSON == name || WTypes.OBJECT == name
  def isStream = WTypes.OBJECT == name && "DieselStream" == schema

  def isSubtypeOf (subtype: WType) = WTypes.isSubtypeOf (this, subtype)

  def nonEmpty = name.nonEmpty
}

