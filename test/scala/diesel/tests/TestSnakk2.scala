/**
  *   ____    __    ____  ____  ____,,___     ____  __  __  ____
  *  (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
  *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
  *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
  */
package tests

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest.{MustMatchers, OptionValues, WordSpecLike}
import razie.diesel.engine.{DieselAppContext, DomEngineSettings}
import razie.diesel.samples.DomEngineUtils
import razie.wiki.parser.DieselTextSpec

/**
  * Created by raz on 2017-07-06.
  */
class TestSnakk2 extends WordSpecLike with MustMatchers with OptionValues {

  "RestClient" should {
    "work" in {

      assert("Jane" contains "Janx")
    }
  }
}

class SS2 {

}
