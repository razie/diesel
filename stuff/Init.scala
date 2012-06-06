package admin

import model.Enc
import model.Mongo
import model.User
import model.UserGroup
import model.Users
import model.WID
import model.WikiEntry
import razie.Logging

object Init extends Logging {

  def e(s: String) = Enc(s)

  /** initialize the database */
  def initDb() = {
  }
  
  def initDbOLD() = {
    audit("INIT DB")

    for (
      t <- Seq(
          "UserGroup", "User", "Profile", "UserEvent", "Task", "UserTask", "UserWiki", 
       "WikiEntry", "WikiEntryOld", "WikiLink", 
       "Audit",
          "Category", "Stuff"
          )
    ) Mongo.db.getCollection(t).drop

    import model.Perm._
    
    val groups = List(
      UserGroup("admin", Set(uProfile.plus, cCategory.plus, uCategory.plus, uReserved.plus, uWiki.plus, adminDb.plus)),
      UserGroup("racer", Set(uProfile.plus, uWiki.plus)),
      UserGroup("parent", Set(uProfile.plus, uWiki.plus)))
    groups map (ug => Users.create(ug))

    val users = List(User ("andrei", "Andrei", "Cojocaru", 2000, e("a@racerkidz.com"), e("asdf")),
      User ("matei", "Matei", "Cojocaru", 2002, e("m@racerkidz.com"), e("asdf")),
      User ("razie", "Razie", "Cojocaru", 1970, e("r@racerkidz.com"), e("asdf"), 'a', Set("admin")),
      User ("ileana", "Ileana", "Cojocaru", 1969, e("i@racerkidz.com"), e("asdf"), 'a', Set("admin")))
    users map (ug => ug.create(ug.mkProfile))

    val tasks = List (
      model.Task("addParent", "Add a parent to your account"),
      model.Task("verifyEmail", "Verify your email address"))
    tasks map (ug => Users.create(ug))

    def w(cat: String, name: String, wiki: String = "") = 
      WikiEntry (cat, model.Wikis formatName name, name, model.Wikis.MD, wiki, "initial")
      
    def wr(cat: String, name: String, wiki: String = "") = w(cat, name, wiki).props(Map("reserved" -> "yes"))

    val pages = List(
      wr("Category", "Category", "Reserved page for the category itself"),
      wr("Category", "Link", "Reserved page for the Link category"),
      wr("Category", "User", "Reserved page for users"),
      wr("Category", "Page", "Reserved page"),
      wr("Category", "Alias", "Reserved page for aliases"),
      wr("Category", "WikiLink", "Reserved page for links"),

      wr("Link", "Member", "Reserved page for the Link category"),
      wr("Link", "Enjoy", "Reserved page for the Link category"),

      w("Category", "Sport"),
      w("Category", "Series"),
      w("Category", "Club"),

      wr("Task", "addParent", "You need to add a parent to your account before you can do a bunch of stuff..."),
      wr("Task", "verifyEmail", "You need to verify your email address before using your account further..."),

      wr("Page", "Nothing"),
      wr("Page", "home", "{{redirect:/}}"),
      wr("Page", "Terms_of_Service", terms),
      wr("Page", "wiki", "Main wiki entry page - please follow a category: [[list:Category]]"))
    pages map (_.create)

      def aliasTo(to: WID, from: WID) =
        w(from.cat, from.name, "[[alias:%s/%s]]".format(to.cat, to.name))
      def aliasFrom(from: WID, to: WID*) =
        w(from.cat, from.name, to.map(p => "[[alias:%s/%s]]".format(p.cat, p.name)).mkString("\n"))

    val SPORT = "Sport"
      def a(name: String, wiki: String = "") = w(SPORT, name, wiki)
      def aalias(to: String, name: String) = aliasTo(WID(SPORT, to), WID(SPORT, name))
      def aaliasFrom(from: String, to: String*) = aliasFrom(WID(SPORT, from), to.map(WID(SPORT, _)): _*)
    val sports = List(
      a("Slalom"),
      a("Giant Slalom"),
      aalias("Giant Slalom", "GS"),
      a("Super Giant Slalom"),
      aalias("Super Giant Slalom", "SG"),
      a("Downhill"),
      a("SkiCross"),
      a("Cross-country (Ski)"),

      aaliasFrom("XC", "Cross-country (Ski)", "Cross-country (Moto)", "Cross-country (Bike)"),
      aaliasFrom("Trail riding", "Trail riding (Moto)", "Trail riding (Bike)"),

      a("Trail Riding (Moto)"),
      a("Cross-country (Moto)"),
      a("Motocross"),
      a("Supercross"),
      a("Endurocross"),
      a("Enduro"),

      a("Trail Riding (Bike)"),
      a("Cross-country (Bike)"))

    sports map (_.create)

  }

  val terms =
    """
<h2>Terms of Service!</h2>
This service is not a critical service and is provided as is! No warranties expressed or implied.

<h2>Privacy!</h2>
We do not share your information with 3rd parties.

**Ads:** the providers of the ads that may be displayed on this site may track you using 
their usual tracking methods and may or may not provide an "opt out" feature.

TODO 
    
    - terms - no liability
    - privacy - no sharing/using 
    - information in the public wiki - own/CC. find wikimedia license
    - children - special stuff
    
    
<h2>Children under 13</h2>
This service is open to anyone. For users under 13 years of age, certain safety features...
    
 * personally identifiable information is secured with several layers of encryption
 * information is not shared with 3rd parties - Note the exception about the advertisers
 * a parent account is created and verified. The "parent" can permit the child certain 
 * cannot make their profile public
 * comments and all their activity is fully moderated
"""

val nohtml = """
Some html tags are restricted
 
<script haha>hehe</script>
  <object>hoho</object>
  <frame hi hi/>
  <small>kuku</small>
  
  <table><tr><td>1</td<td>2</td></tr><tr><td>3</td<td>4</td></tr></table>
"""
  
}
