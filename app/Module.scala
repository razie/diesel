/**
 *    ____    __    ____  ____  ____,,___     ____  __  __  ____
 *   (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *   )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 *  (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */

import java.util.Properties
import admin._
import akka.cluster.{MemberStatus, Cluster}
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import com.google.inject.AbstractModule
import controllers._
import mod.book.Progress
import razie.db._
import model._
import play.api.Application
import play.api._
import play.api.mvc._
import razie.wiki.util.{AuthService, PlayTools, IgnoreErrors, VErrors}
import razie.{cout, Log, cdebug, clog}
import play.libs.Akka
import akka.actor.{RootActorPath, Props, Actor}
import com.mongodb.casbah.{MongoDB, MongoConnection}
import com.mongodb.casbah.Imports._
import java.io.File
import scala.concurrent.{Future, ExecutionContext}
import controllers.ViewService
import razie.wiki.model.WikiCount
import razie.wiki.admin._
import razie.wiki.{WikiConfig, Alligator, EncryptService, Services}
import razie.wiki.model.WikiAudit
import razie.wiki.model.WikiUsers
import razie.wiki.model.WikiUser
import razie.wiki.model.Reactors
import razie.wiki.model.WikiEntry
import razie.wiki.model.Reactor
import razie.wiki.Sec._

import scala.util.Try

/** NOT WORKING !!!!!!!!!! */
class Module extends AbstractModule {
  razie.clog << "HIIIIIIIIIIIIIIIIII"
  def configure() = {
    razie.clog << "HAAAAAAAAAAAAAAAAAAAAAAAA"
//    bind[AuthService].to[RazAuthService]
  }
}

