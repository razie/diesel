package mod.cart

import controllers.{Emailer, Profile}
import model.{ExtSystemUserLink, User, Users, UsersPersist}
import org.joda.time.DateTime
import razie.clog
import razie.db.RMany
import razie.diesel.Diesel
import razie.diesel.dom.RDOM.P
import razie.diesel.dom.WTypes
import razie.diesel.engine.{DieselException, DomAst}
import razie.diesel.engine.exec.EExecutor
import razie.diesel.engine.nodes.{EMsg, EVal, MatchCollector}
import razie.diesel.expr.ECtx
import razie.wiki.model._
import razie.wiki.util.UsersPersistDiesel
import razie.wiki.{Config, Enc, Services}

/** user management
  *
  * todo these should be signed and authorized?
  */
object EEModUserExecutor extends EExecutor("diesel.mod.user") {

  override def isMock: Boolean = true

  override def test(ast: DomAst, m: EMsg, cole: Option[MatchCollector] = None)(implicit ctx: ECtx) = {
    m.entity == "diesel.mod.user" || m.entity == "mod.user"
  }

  override def apply(in: EMsg, destSpec: Option[EMsg])(implicit ctx: ECtx): List[Any] = {
    razie.db.tx("ModUserExec", "?") { implicit txn =>
      in.met match {

        case "canstatus" => { // can user upgrade to level?
          //todo auth
          var msg = "false"

          val realm = ctx("realm")
          val level = ctx("level")
          val userId = ctx("userId")

          Users.findUserById(userId).map(_.forRealm(realm)).map {u=>
            val perm = level match {
              case "renew:blue" => u.hasPerm(Perm.Basic)
              case "renew:black" => u.hasPerm(Perm.Basic) || u.hasPerm(Perm.Gold)     // can upgrage this way
              case "renew:racing" => u.hasPerm(Perm.Gold) || u.hasPerm(Perm.Platinum) || u.hasPerm(Perm.Unobtanium) // can upgrage this way
              case "blue" | "black" | "racing" => u.hasMembershipLevel(Perm.Member.s)
              case _ => false
            }

            msg = perm.toString
          }

          List(new EVal("payload", msg))
        }

        case "updstatus" => { //  user paid, udpate membership
          //todo auth
          var msg = "ok"
          clog << "mod.user.updstatus"
          val realm = ctx("realm")
          val level = ctx("level")
          val amount = ctx("paymentAmount")
          val paymentId = ctx("paymentId")
          val userId = ctx("userId")

          Emailer.withSession(realm) { implicit mailSession =>
            if (paymentId.startsWith("PAY") && paymentId.length > 20) {
              // ok, update
              Users.findUserById(userId).map(_.forRealm(realm)).map {u=>
                val perm = level match {
                  case "blue" | "renew:blue" | Perm.Basic.s => Some(Perm.Basic)
                  case "black" | "renew:black" | Perm.Gold.s => Some(Perm.Gold)
                  case "racing" | "renew:racing" | Perm.Platinum.s => Some(Perm.Platinum)
                  case _ => None
                }

                var newu = u.addModNote(
                  realm,
                  s"${DateTime.now().toString} - membership upgraded to $level with payment $paymentId amount $amount"
                )

                if(perm.isDefined && !u.hasMembershipLevel(perm.get))
                  newu = newu.addPerm(realm, "+"+perm.get.s)

                // in case it was expired
                newu = newu.removePerm(realm, "+"+Perm.Expired.s)

                u.update(newu)
                Services.auth.cleanAuth2(u)

                Emailer.tellSiteAdmin(s"User ${u.userName} paid membership upgraded to: $level")
              }.getOrElse {
                Emailer.tellSiteAdmin(s"Some problem with user payment uid ${userId} to: $level")
              }
            } else {
              // oops? what payment?
              val uname = Users.findUserById(userId).map (_.userName).mkString
              Emailer.tellSiteAdmin(s"Payment ID invalid uid ${userId} uname $uname to: $level paymentId $paymentId amount $amount")
            }
          }

          List(new EVal("payload", msg))
        }

        case "createuser" => { //  user paid, udpate membership

          var msg = ""
          clog << "diesel.mod.user.createuser"

          // todo authorize trusted realm
          val inRealm = ctx("inRealm")
          if(inRealm.length == 0)
            return List(new EVal("need inRealm", msg))
          if(Wikis(inRealm).realm != inRealm)
            return List(new EVal("inRealm not valid", msg))

          if(ctx.root.settings.userId.isEmpty)
            return List(new EVal("failed auth", msg))

          //todo auth
          val fromRealm = ctx.root.settings.realm.mkString
          val authInfo = ctx("authInfo")

          val f = ctx("first").trim
          val l = ctx("last").trim
          val e = ctx("email").trim
          val p = ctx("password").trim
          val org = ctx("organization").trim
          val y = 0
          if(e.length == 0)
            return List(new EVal("need email", msg))

          val esid = ctx("extSystemId").trim
          val eiid = ctx("extInstanceId").trim
          val eaid = ctx("extAccountId").trim

          def dfltCss = Services.config.sitecfg("dflt.css") getOrElse "light"

          val trust = Services.config
              .sitecfg("diesel.mod.user.trustRealms")
              .mkString
              .split(",")

          Emailer.withSession(ctx.root.settings.realm.mkString) { implicit mailSession =>
            if (
              // only if this realm is trusted
              trust.contains(ctx.root.settings.realm.mkString)
            ) {
              var ou = Users.findUserByEmailDec(e)

              if(ou.isEmpty) {
                // new user - create
                val u = User(
                  Users.uniqueUsername(Users.unameF(f, l, y)), f.trim, l.trim, y,
                  Enc(e.trim),
                  Some(Enc(e.toLowerCase)),
                  Enc(p),
                  'a',
                  Set(Users.ROLE_MEMBER),
                  Set(inRealm),
                  None,
                  Map(
                    "css" -> dfltCss,
                    "favQuote" -> "Do one thing every day that scares you - Eleanor Roosevelt",
                    "weatherCode" -> "caon0696"
                  )
                ).copy (
                  organization = Some(org)
                )

                razie.db.tx("doeCreateExtAuto", u.userName) { implicit txn =>
                  var newu = Profile.createUser(u, "auto-user", inRealm, None).get
                  newu = newu.addModNote(
                    inRealm,
                    s"${DateTime.now().toString} - user auto-created diesel.mod.user from realm:$fromRealm"
                  )
                  newu = newu
                      .addPerm(inRealm, Perm.eVerified.s).addPerm(inRealm, Perm.uWiki.s)
                      .addPerm("*", Perm.eVerified.s).addPerm("*", Perm.uWiki.s)
                      .addPerm(inRealm, Perm.uProfile.s)
                      .addPerm("*", Perm.uProfile.s)

                  val p = newu.profile.get
                  val newp = p.upsertExtLink(inRealm, ExtSystemUserLink(inRealm, esid, eiid, eaid))
                  p.update(newp)
                  ou = Some(newu)
                  newu.update(newu)
                }

                Emailer.tellSiteAdmin(s"Auto-created User ${u.userName}  email: $e")
                Emailer.tellRaz(s"Auto-created User ${u.userName} email: $e")
              } else if(! ou.get.hasRealm(inRealm)) {
                //existing user - add to realm
                val u = ou.get

                var newu = u.addModNote(
                  inRealm,
                  s"${DateTime.now().toString} - user auto-added to realm $inRealm fromRealm $fromRealm"
                )

                newu = Users.updRealm(newu, inRealm)
                u.update(newu)
                Services.auth.cleanAuth2(u)

                Emailer.tellRaz(s"Auto-updadded User ${u.userName} email: $e")
              }
              msg="ok"
            } else {
              // oops? hack?
              Emailer.tellRaz(
                s"diesel.mod.user.create - hack?",
                s"fromRealm:$fromRealm, email:$e"
              )
              msg = "failed auth/trust"
            }
          }

          List(new EVal(Diesel.PAYLOAD, msg))
        }

        case "getUser" => {
          val email = ctx.getRequired("email")
          val u = Users.findUserByEmailDec(email)
          if(u.nonEmpty)
            u.toList.map(x=>new EVal(Diesel.PAYLOAD, x.toJson))
          else
            List(new EVal(P.undefined(Diesel.PAYLOAD)))
        }

        case "getProfile" => {
          val email = ctx.getRequired("email")
          val u = Users.findUserByEmailDec(email)
          if(u.nonEmpty)
            u.toList.map(x=>new EVal(P.fromTypedValue(Diesel.PAYLOAD, x.profile.get.toJson, WTypes.JSON)))
          else
            List(new EVal(P.undefined(Diesel.PAYLOAD)))
        }

        case "findProfileByUserId" => {
          val u = Users.findProfileByUserId(ctx.getRequired("userId"))
          if(u.nonEmpty)
            u.toList.map(x=>new EVal(P.fromTypedValue(Diesel.PAYLOAD, x.toJson, WTypes.JSON)))
          else
            List(new EVal(P.undefined(Diesel.PAYLOAD)))
        }

        case "toDiesel" => {
          val email = ctx.getRequired("email")
          val u = Users.findUserByEmailDec(email)
          u.map {u=>
            val per = new UsersPersistDiesel()
            per.setDefaultRealm(ctx.root.settings.realm.mkString)
            val res1 = per.createUser(_)
            val p = Users.findProfileByUserId(u._id.toString)
            val res2 = p.map(new UsersPersistDiesel().createProfile(_))
          }
          List(new EVal(Diesel.PAYLOAD, "ok"))
        }

        case "setPersist" => {
          if(Services.config.isLocalhost) {
          val cls = ctx.getRequired("class")
          Users.persist = Class.forName(cls).newInstance().asInstanceOf[UsersPersist]
          Users.persist.setDefaultRealm(ctx.root.settings.realm.mkString)
          List(new EVal(Diesel.PAYLOAD, "ok, " + Users.persist.getClass.getSimpleName))
          } else
            throw new DieselException("Only available on localhost")
        }

        case "migrateToElk" => {
          if(Services.config.isLocalhost) {
            val p = new UsersPersistDiesel()
            p.setDefaultRealm(ctx.root.settings.realm.mkString)
            val count = p.migrate()
            List(new EVal(Diesel.PAYLOAD, "migrated " + count))
          } else
            throw new DieselException("Only available on localhost")
        }

        case "migrateLower" => {
          if(Services.config.isLocalhost) {
          var count = 0
          RMany[User]().foreach{u =>
            razie.Log("MIgrating user: " + u.emailDec)
            u.update(u.copy(emailLower = Some(Enc(u.emailDec.toLowerCase))))
            count += 1
          }
          List(new EVal(Diesel.PAYLOAD, "migrated " + count))
        } else
      throw new DieselException("Only available on localhost")
      }

        case _ => {
          Nil
        }
      }
    }
  }

  override def toString = "$executor::diesel.mod.user "

  override val messages: List[EMsg] = List(
    "canstatus",
    "updstatus",
    "createuser",
    "getUser", "getProfile", "toDiesel",
    "migrateToElk",
    "migrateLower"
  ).map (EMsg("diesel.mod.user", _))
}

