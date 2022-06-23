package wiki

import org.scalatest.{MustMatchers, OptionValues, WordSpecLike}
import razie.wiki.model.WID

/**
  * run like: sbt 'testOnly *TestWikis'
  */
class TestWikis extends WordSpecLike with MustMatchers with OptionValues {

  def w(s:String) = WID.fromPath(s)

  "WID parser" should {

    "parse ok" in {
      assert(w( "x.Spec:atom_spec" ).exists(_.name == "atom_spec"))
    }

    "parse -" in {
      assert(w( "omni-prod.Spec:atom_spec" ).exists(_.name == "atom_spec"))
    }
  }

}

