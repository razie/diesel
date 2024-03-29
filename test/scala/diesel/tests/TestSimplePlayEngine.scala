/**
  *   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package tests

import org.scalatestplus.play._
import play.api.inject.guice._
import play.api.{Application, Play}
import razie.diesel.engine.DomEngineSettings
import razie.diesel.samples.DomEngineUtils

/**
  * Created by raz on 2017-07-06.
  */
class TestSimplePlayEngine extends PlaySpec /*with GuiceOneAppPerSuite */ {

  import SampleSpecs1._

  val application: Application = new GuiceApplicationBuilder()
    .configure("some.configuration" -> "value")
    .build()
  Play.start(application)

  "simple specs" should {
    "execute a message" in {
      val engine = DomEngineUtils.execAndWait(
        DomEngineUtils.mkEngine(new DomEngineSettings(), specs, List(storySend)))
      println(engine.root.toString)
      println(engine.resultingValue)
      assert(engine.resultingValue contains "Jane")
    }
  }
}
