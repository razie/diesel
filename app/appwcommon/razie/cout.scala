/**
 *   ____    __    ____  ____  ____,,___     ____  __  __  ____
 *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie

/** c++ memories, anyone... i do like to use the cout << x instead of println(x) */
object cout {
  def <(x: Any) = { println("< " + x); this }
  def <<(x: Any) = { println("<<  " + x); this }
  def <<<(x: Any) = { println("<<<   " + x); this }

  def |(x: Any) = this < x
  def ||(x: Any) = this << x
  def |||(x: Any) = this <<< x
  
  def eol = {println(); this}
}

/** c++ memories, anyone... i do like to use the cout << x instead of println(x) */
object clog extends Logging {
  def <(x: Any) = { log("< " + x); this }
  def <<(x: Any) = { log("<<  " + x); this }
  def <<<(x: Any) = { log("<<<   " + x); this }

  def |(x: Any) = this < x
  def ||(x: Any) = this << x
  def |||(x: Any) = this <<< x
  
  def eol = {this}
}

/** c++ memories, anyone... i do like to use the cout << x instead of println(x) */
object cdebug extends Logging {
  def <(x: Any) = { debug("< " + x); this }
  def <<(x: Any) = { debug("<<  " + x); this }
  def <<<(x: Any) = { debug("<<<   " + x); this }

  def |(x: Any) = this < x
  def ||(x: Any) = this << x
  def |||(x: Any) = this <<< x
  
  def eol = {this}
}

