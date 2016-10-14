package admin

import model.{User, UserGroup, Users}
import razie.Logging
import razie.db.RazMongo
import razie.wiki.Enc
import razie.wiki.model.{Wikis, WikiEntry}

/** not really used anymore - used in the beginning to reset the database */
object Init extends Logging {

  def e(s: String) = Enc(s)

  /** initialize the database */
  def initDb() = {

    audit("INIT DB")

    for (
      t <- Seq(
        "Audit",
        "AuditCleared",
        "Comment",
        "CommentStream",
        "DoSec",
        "OldStuff",
        "ParentChild",
        "Profile",
        "RegdEmail",
        "Stage",
        "Task",
        "User",
        "UserEvent",
        "UserGroup",
        "UserOld",
        "UserTask",
        "UserWiki",
        "Ver",
        "WikiAudit",
        "WikiCount",
        "WikiEntry",
        "WikiEntryOld",
        "WikiLink")
    ) RazMongo(t).drop

    import model.Perm._

    RazMongo("Ver") += Map("ver" -> 1) // create a first ver entry

    val groups = List(
      UserGroup("admin", Set(uProfile.plus, adminWiki.plus, uWiki.plus, adminDb.plus)),
      UserGroup("Student", Set(uProfile.plus, uWiki.plus)),
      UserGroup("Teacher", Set(uProfile.plus, uWiki.plus)))
    groups map (_.create)

    val razie = User("razie", "Razvan", "Cojocaru", 1970, e("razie@razie.com"), e("asdf"), 'a', Set("admin"))
    val users = List(razie)
    users map (ug => ug.create(ug.mkProfile))


    val tasks = List(
      model.Task("addParent", "Add a parent to your account"),
      model.Task("verifyEmail", "Verify your email address"))
    tasks map (ug => Users.create(ug))

    def w(cat: String, name: String, wiki: String = "") =
      WikiEntry(cat, Wikis formatName name, name, Wikis.MD, wiki, razie._id)

    def wr(cat: String, name: String, wiki: String = "") = w(cat, name, wiki).cloneProps(Map("reserved" -> "yes"), razie._id)

    val pages = List(
      wr("Category", "Category", "Reserved page for the category itself"),
      wr("Category", "User", "Reserved page for users"),
      wr("Category", "WikiLink", "Reserved page for links"),
      wr("Category", "Admin", "Reserved pages "),
      wr("Category", "Topic", "Reserved pages "),

      wr("Task", "addParent", "You need to add a parent to your account before you can do a bunch of stuff..."),
      wr("Task", "verifyEmail", "You need to verify your email address before using your account further..."),

      wr("Page", "Nothing"),
      wr("Page", "home", "{{redirect:/}}"),
//      wr("Page", "Terms_of_Service", terms),
      wr("Page", "wiki", "Main wiki entry page - please follow a category: [[list:Category]]"))
    pages map (_.create)
  }

}
