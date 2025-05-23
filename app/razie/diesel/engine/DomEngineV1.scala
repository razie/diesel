/*  ____    __    ____  ____  ____  ___     ____  __  __  ____
 * (  _ \  /__\  (_   )(_  _)( ___)/ __)   (  _ \(  )(  )(  _ \           Read
 *  )   / /(__)\  / /_  _)(_  )__) \__ \    )___/ )(__)(  ) _ <     README.txt
 * (_)\_)(__)(__)(____)(____)(____)(___/   (__)  (______)(____/    LICENSE.txt
 */
package razie.diesel.engine

import org.bson.types.ObjectId
import razie.audit.Audit
import razie.js
import razie.diesel.Diesel
import razie.diesel.Diesel.PAYLOAD
import razie.diesel.dom.RDOM.P
import razie.diesel.dom.{RDomain, WTypes}
import razie.diesel.engine.RDExt._
import razie.diesel.engine.exec.{EApplicable, Executors}
import razie.diesel.engine.nodes.StoryNode
import razie.diesel.engine.nodes._
import razie.diesel.expr._
import razie.diesel.model.DieselMsg
import razie.diesel.model.DieselMsg.ENGINE._
import razie.hosting.{Website, WikiReactors}
import razie.hosting.WikiReactors.reactors
import razie.tconf.DSpec
import razie.wiki.model.DCNode
import razie.wiki.{Config, Services}
import scala.Option.option2Iterable
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.util.Try
import services.DieselCluster

/** the actual engine implementation
  *
  * we expand and execute the known nodes
  */
class DomEngineV1 (
  dom: RDomain,
  root: DomAst,
  settings: DomEngineSettings,
  pages: List[DSpec],
  description: String,
  correlationId: Option[DomAssetRef] = None,
  id: String = new ObjectId().toString)
    extends DomEngine (
      dom, root, settings, pages, description, correlationId, id
    ) {

  assert(settings.realm.isDefined, "need realm defined for engine settings")

  /** transform one element / one step
    *
    * 1. you can collect children of the current node, like info nodes etc
    * 2. return continuations, including processing through the children
    *
    * @param a       node to decompose
    * @param recurse should recurse
    * @param level
    * @return continuations for this branch, if any
    */
  protected override def expand(a: DomAst, recurse: Boolean = true, level: Int): List[DEMsg] = {
    var msgs : List[DEMsg] = Nil // continuations from this cycle

    // link the spec - some messages get here without a spec, because the DOM is not available when created
    if (a.value.isInstanceOf[EMsg] && a.value.asInstanceOf[EMsg].spec.isEmpty) {
      val m = a.value.asInstanceOf[EMsg]
      dom.moreElements.collect {
        case s: EMsg if s.entity == m.entity && s.met == m.met => m.withSpec(Some(s))
      }.headOption
    }

    // +10 because it should normally fail in ruleDecomp first - see there
    if (level >= maxLevels+10) {
      if(findParentWith(a, _.tailrec).isDefined) {
//        trace("ignoring @tailrec")
      } else {
        evAppChildren(a,
        DomAst(
          TestResult(
            "fail: maxLevels!", "b.You have a recursive rule generating this branch - if it's ok, then set `diesel.levels(max=100)`"),
          "error"))
      evAppChildren(a,
        DomAst(
          EMsg("diesel", "throw",
            List(new P("error", s"Recursive rule (maxLevels=$maxLevels)"))
          )))
        return Nil
      }
    }

    if (this.curExpands > maxExpands && !Services.config.isLocalhost) {
      // THIS IS OK: append direct to children
      a append DomAst(
        TestResult(
          "fail: maxExpands!", s"You have expanded too many nodes (>$maxExpands)..."), "error")
      return Nil //stopNow
    }

    a.value match {

      case stop: EEngStop => {
        // stop engine
        evAppChildren(a, DomAst(EInfo(s"""Stopping engine $href""")))//.withPos((m.get.pos)))
        stopNow()
      }

      case stop: EEngSuspend => {
        // suspend and wait for a continuation async
        evAppChildren(a, DomAst(EInfo(s"""Suspending engine $href""")))
        try {
          stop.onSuspend.foreach(_.apply(this, a, level))
        } catch {
          case e: Exception => {
            msgs = rep(a, recurse, level, List(
              DomAst(new EError("While calling suspend callback", e), AstKinds.ERROR)
            ))
          }
        }
      }

      case stop: EEngComplete => {
        evAppChildren(a, DomAst(EInfo(s"""Complete suspended node... """)))
      }

      case next@ENext(m, arrow, cond, _, _) if "==>" == arrow || "<=>" == arrow => {
        // forking new engine / async branch

        implicit val ctx = a.withCtx(mkPassthroughMsgContext(
          next.parent,
          reconcileParentAttrs(next.parent.map(_.attrs).getOrElse(Nil), a.getCtx.get),
          a.getCtx.get, //RAZ2 this.ctx,
//          this.ctx, // todo probably use a.getCtx.get not this.ctx
          a))

        // start new engine/process - evaluate this message as the root of new engine
        // todo should find settings for target service  ?
        val msg = next.evaluateMsgCall
        var correlationId = DomAssetRef(DomRefs.CAT_DIESEL_ENGINE, this.id)

        // for this pattern, add a suspend and set correlationId
        if ("<=>" == arrow) {
          val suspend =
            DomAst(EEngSuspend("spawn <=>", "Waiting for spawned engine", Some((e, a, l) => {
            })))

          // make sure the other engine knows it has to notify me
          correlationId = DomAssetRef(DomRefs.CAT_DIESEL_ENGINE, this.id, None, Some(suspend.id))
          //old: correlationId = correlationId.id + "." + suspend.id

          msgs = rep(a, recurse, level, List(suspend))
        }

        // create new engine
        val ref = spawn (msg, Option(correlationId))

        evAppChildren (a, DomAst(EInfo(s"""Spawn $arrow engine ${ref._1.href} (${ref._2})""")))
        val ev = EVal(P.fromTypedValue("dieselRef", ref._1.mkEngRef, WTypes.REF))
        evAppChildren (a, DomAst(ref))
        setSmartValueInContext(a, ctx, ev)

        if ("<=>" == arrow) {
          // nothing special here, I'm already about to Suspend (see above) and child will send a pong when done
        }

        // else no need to return anything - children have been decomposed
      }

      case n1@ENext(m, ar, cond, _, _) if "-" == ar || "=>" == ar => {
        // message executed later

        implicit val ctx = a.replaceCtx (mkPassthroughMsgContext(
          n1.parent,
          // this was a BIG issue, i..e (queryParms = won't work with this in a diesel.rest)
          // this was an issue - this is why overriden parameters with values don't work, because this copies over the parms from parent message
          reconcileParentAttrs (n1.parent.map(_.attrs).getOrElse(Nil), a.getCtx.get),
          a.getCtx.get, //RAZ2 this.ctx,
          a))

        if (n1.test(a)) {
          val newmsg = n1.evaluateMsgCall
          val newnode = DomAst(newmsg, AstKinds.kindOf(newmsg.arch))

          // old node may have had children already
          newnode.moveAllNoEvents(a)

          msgs = rep(a, recurse, level, List(newnode))
        } else {
//          a.parent.foreach { p =>
            evAppChildren(a,
              DomAst(EInfo("$if/else was false: " + n1.cond.mkString).withPos(n1.msg.pos), AstKinds.TRACE))
        }
        // message will be evaluate() later
      }

      case n1@ENext(m, ar, cond, _, _) if "xxx=>>" == ar => {
        // message executed in separate engine context

        implicit val ctx = a.withCtx(mkPassthroughMsgContext(
          n1.parent,
          reconcileParentAttrs(n1.parent.map(_.attrs).getOrElse(Nil), a.getCtx.get),
          a.getCtx.get, //RAZ2 this.ctx,
          a))

        if (n1.test(a)) {
          val newmsg = n1.evaluateMsgCall
          val newnode = DomAst(newmsg, AstKinds.kindOf(newmsg.arch))

          // old node may have had children already
          newnode.moveAllNoEvents(a)

          val newe = DieselAppContext.mkEngine(
            this.dom,
            newnode,
            this.settings,
            this.pages,
            "SYNC-" + this.description
          )

          val level = findLevel(a)

          // a message with this name found, call it sync

          // NOTE - need to use ctx to access values in context etc, i..e map (x => a.b(x))
          val res = newe.processSync(newnode, level, ctx, true)

          newnode.setKinds(AstKinds.TRACE)
          newnode.kind = AstKinds.SUBTRACE

          // save the trace in the main tree
          a.appendAllNoEvents(List(newnode))

          appendVals(a, m.pos, Some(m), res.toList, ctx, AstKinds.kindOf(m.arch))
        } else {
//          a.parent.foreach { p =>
            evAppChildren(a,
              DomAst(EInfo("$if/else was false:: " + n1.cond.mkString).withPos(n1.msg.pos), AstKinds.TRACE))
        }
        // message will be evaluate() later
      }

      // simple assignment
      case n1@ENextPas(m, ar, cond, _, _) if "-" == ar || "=>" == ar => {

        // ctx needed for the test below
        implicit val ctx = a.getCtx.get

        if (n1.test(a)) {
          // run in parent's context - no need for a local
          appendValsPas(Some(a), m.pos, Some(m), m.attrs, a.getCtx.get)
        } else {
//          a.parent.foreach { p =>
            evAppChildren(a,
              DomAst(EInfo("$if/else was false::: " + n1.cond.mkString).withPos(n1.msg.pos), AstKinds.TRACE))
        }
      }

      // not so simple assignment
      case n1@EMsgPas(ar) => {

        // run in parent's context - no need for a local
        appendValsPas(Some(a), n1.pos, Some(n1), n1.attrs, a.getCtx.get)
      }

      case x: EMsg if x.entity == "" && x.met == "" => {
        implicit val ctx = a.withCtx(mkPassthroughMsgContext(
          None,
          Nil,
          a.getCtx.get, //RAZ2 this.ctx,
          a))

        // just expressions, generate values from each attribute
        appendVals(a, x.pos, Some(x), x.attrs, ctx, AstKinds.kindOf(x.arch))
      }

      case in: EMsg => {

        // todo should collect all parent contexts from root to here...
        // todo problem right now: parent parms are not in context, so can't use in child messages

        // node context created in expandEMsg
        msgs = expandEMsgAndRep(a, in, recurse, level, a.getCtx.getOrElse(ctx)) ::: msgs
      }

      case n: EVal if !AstKinds.isGenerated(a.kind) => {

        // need a syntetic context so that there is later a context of curNode (for stiching traces etc)
        implicit val ctx = new StaticECtx (Nil, a.getCtx, Some(a))
        // $val defined in scope in story, not in spec.
        val p = n.p.calculatedP // only calculate if not already calculated =likely in a different context=

        appendVals(a, n.pos, None, List(p), a.getCtx.get, AstKinds.DEBUG)
      }

      case e: ExpectM => if (!settings.simMode) expandExpectM(a, e)

      case e: ExpectV => if (!settings.simMode) expandExpectV(a, e)

      case e: ExpectAssert => if (!settings.simMode) expandExpectAssert(a, e)

      case st: StoryNode => {

        // re-add the story elements
        val nodes = a.children
        nodes.foreach(_.resetParent(null))
        a.childrenCol.clear()
        msgs = rep(a, recurse, level, nodes)
      }

      case e: EError => {

        // todo complete this so it throws on any err right away

        // todo used to do it like this, at the scope level:
        if (findParentWith(a, _.value.isInstanceOf[EScope]).exists(_.value.asInstanceOf[EScope].isStrict)) {

//       todo should we use the same string? per engine
//        if (ctx.root.isStrict) {

          val ex = e.t.map(P.of("exception", _)).toList
          val msg = if (e.t.isEmpty) List(P.of("msg", e.details)) else Nil
          a append DomAst(new EMsg("diesel", "throw", ex ::: msg), AstKinds.GENERATED)
        }
      }

      case e: InfoNode =>  // ignore
      case e: EVal =>  // ignore - (a = b) are executed as EMsgPas / NextMsgPas - see appendValsPas

      case s: ERule => // ignore - can't execute rules or mocks
      case s: EMock => // ignore - can't execute rules or mocks

      // todo should execute
      case s: EApplicable => {

        clog << "EXEC KNOWN: " + s.toString
        evAppChildren(a,
          DomAst(EError("Can't EApplicable KNOWN - " + s.getClass.getSimpleName, s.toString), AstKinds.ERROR))
      }

      case s@_ => {

        clog << "NOT KNOWN: " + s.toString
        evAppChildren(a, DomAst(
          EWarning("NODE NOT KNOWN - " + s.getClass.getSimpleName, s.toString),
          AstKinds.GENERATED
        ))
      }
    }
    msgs
  }

  /** expand a message - main entry point */
  protected override def expandEMsg(a: DomAst, in: EMsg, recurse: Boolean, level: Int, parentCtx: ECtx):
  (List[DomAst], List[DomAst])
  = {
    var newNodes: List[DomAst] = Nil // nodes generated this call collect here
    var skippedNodes: List[DomAst] = Nil // nodes skipped during this call collect here

    implicit var ctx = parentCtx

    // expand message expr if the message attrs were expressions, calculate their values
    val n: EMsg = calcMsg(a, in)(ctx)
    // replace it so we see the calculated values
    a.value = n

    if (n.ea == "diesel.breakpoint") {
      // put breakpoint here
      clog << n.ea
    }

    // special warning for using payload
    if (n.attrs.exists(_.name == PAYLOAD))
      newNodes = newNodes ::: List(
        DomAst(new EError(
          "Should not use 'payload' as an argument, it's a reserved keyword and has unpredictable " +
              "consequences! at " + in.pos + " rule " + in.rulePos + " spec" + in.specPos +
        "msg " + in),
          AstKinds.ERROR))

    //todo used to set kind to generated but parent makes more sense.getOrElse(AstKinds.GENERATED)
    val parentKind = a.kind match {
      case AstKinds.TRACE | AstKinds.VERBOSE => a.kind
      case _ => AstKinds.GENERATED
    }

    // this is causing BIG WEIRD problems...
//     a.children.append(DomAst(in, AstKinds.TRACE).withStatus(DomState.SKIPPED))
//    a.children.append(DomAst(EInfoWrapper(in), AstKinds.TRACE).withStatus(DomState.SKIPPED))

    // if node already has a context, just use it (stories wrapped in Scope for instance)
    ctx = if (
      a.getMyOwnCtx.exists(_.isInstanceOf[StaticECtx]) ||
          a.getMyOwnCtx.exists(_.isInstanceOf[ScopeECtx]) ||
          a.getMyOwnCtx.exists(_.isInstanceOf[DomEngECtx])) {

      a.getMyOwnCtx.get
    } else {
      // todo should I use reconcileParentAttrs ?
      ctx = mkPassthroughMsgContext(Some(in), n.attrs, parentCtx, a)
      a.withCtx(ctx)
      ctx
    }

    // PRE - did it already have some children, decomposed by someone else?
    newNodes = a.children
    // reset parents
    newNodes.foreach(_.resetParent(null))
    a.childrenCol.clear() // remove them so we don't have duplicates in tree - they will be processed later

    // =============== 1. engine message?

    var (mocked, skipped, moreNodes) = expandEngineEMsg(a, n, newNodes)

    skippedNodes = skippedNodes ::: skipped
    newNodes = newNodes ::: moreNodes

    var mocksApplied = HashMap[String, EMock]()

    // =============== 2. if not, look for mocks

    if (settings.mockMode) {
      val exclusives = HashMap[String, ERule]()

      var mocksFromStory = root.collect {
        // mocks from story AST
        case d@DomAst(m: EMock, _, _, _) if m.rule.e.test(a, n) && a.children.isEmpty => m
      }

      var matchingRules = (mocksFromStory ::: dom.moreElements.toList).collect {
        // todo perf optimize moreelements.toList above
        // plus mocks from spec dom
        case m: EMock if m.rule.e.test(a, n) && a.children.isEmpty => m
      }

      // make sure they are distinct, using hashcode so same element can't be twice
      // todo some story mocks end up in domain too, why ??
      // see bulk-cpe-story, those mocks
      matchingRules = matchingRules.distinct

      // todo do fallbacks for mocks, like in the rules?

      matchingRules
          .sortBy(0 - _.rule.arch.indexOf("exclusive")) // put excl first, so they execute and kick out others
          .map {m=>
          mocked = true
          val exKey = m.rule.e.cls + "." + m.rule.e.met
          mocksApplied.put (exKey, m)

          //filter out exclusives - if other rules with the same name applied, the exclusive will kick out the others
          if(exclusives.contains(exKey)) { // there's an exclusive for this - ignore it
            newNodes = newNodes :::
              DomAst(EInfo("mock excluded", exKey).withPos(m.pos), AstKinds.TRACE) ::
              Nil
        } else {
          if(m.rule.arch.contains("exclusive")) {
            exclusives.put (m.rule.e.cls + "." + m.rule.e.met, m.rule) // we do exclusive per pattern
          }

          // run the mock
          newNodes = newNodes ::: ruleDecomp(level, a, n, m.rule, ctx)
        }
      }
    }

    // =============== 2. rules

    var ruled = false
    // no engine messages fit, so let's find rules
    // todo - WHY? only some systems may be mocked ???
    if ((true || !mocked) && !settings.simMode) {
      var matchingRules = rules
          .toList
          .filter(x => !x.isFallback) // without fallbacks first
          .filter(_.e.testEA(n))
          .filter(x => x.e.testAttrCond(a, n) || {
            // add debug
            newNodes = newNodes ::: DomAst(
              ETrace("rule skipped: " + x.e.toString, x.e.toString + "\n" + x.pos).withPos(x.pos),
              AstKinds.VERBOSE) :: Nil
            false
          })

      val exclusives = HashMap[String, ERule]()

      // no match without fallbacks - use fallbacks?
      if (matchingRules.isEmpty)
        matchingRules =
            rules
                .toList
                .filter(_.isFallback)
                .filter(_.e.testEA(n, None, true))
                .filter(x => x.e.testAttrCond(a, n, None, true) || {
                  // add debug
                  newNodes = newNodes ::: DomAst(
                    ETrace("rule skipped: " + x.e.toString, x.e.toString + "\n" + x.pos).withPos(x.pos),
                    AstKinds.VERBOSE) :: Nil
                  false
                })

      // separate the befores and afters
      val befores = matchingRules.filter(r=> r.arch.indexOf("before") >= 0)
      val afters  = matchingRules.filter(r=> r.arch.indexOf("after") >= 0)
      val others  =
        matchingRules.filter (r=> r.arch.indexOf("before") < 0 &&  r.arch.indexOf("after") < 0)
          .sortBy(0 - _.arch.indexOf("exclusive")) // put excl first, so they execute and kick out others

     // recombine in order
      matchingRules = befores ::: others ::: afters

      matchingRules
          .filter {r=>
            // only apply the rules when mocks did not apply for the same key
              // todo use the mock tag query on the origin of the rules
            val exKey = r.e.cls + "." + r.e.met
            !mocksApplied.contains(exKey)
          }.map { r =>
        // each matching rule
        ruled = true

        //filter out exclusives - if other rules with the same name applied, the exclusive will kick out the others
        val exKey = r.e.cls + "." + r.e.met

        var allowed = true

        if(exclusives.contains(exKey)) { // there's an exclusive for this - ignore it
          newNodes = newNodes :::
              DomAst(EInfo("rule excluded", exKey).withPos(r.pos), AstKinds.TRACE) ::
              Nil
        } else {
          if (r.isExclusive) {
            exclusives.put(r.e.cls + "." + r.e.met, r) // we do exclusive per pattern
          }

          // for diesel.rest we check permissions when the rule is about to be expanded
          // todo maybe for others too, in the middle? why not...

          // also check that it comes from apigw so we don't enforce this on internally created ones in stories etc
          if(DIESEL_REST == n.ea &&
              n.attrs.exists(p =>
                p.name == "fromApigw" && p.value.exists(_.asBoolean == true)
              )) {
            var reason = ""

            val vis = settings.realm.flatMap(Website.forRealm).map(_.dieselVisiblity).getOrElse("public")

            if (vis == "public") {
              // just allow it
            } else if (r.arch.nonEmpty) { // otherwise is there something to override non public?
              val uroles = if (this.settings.user.isDefined) this.settings.user.get.roles else Set.empty[String]
              val arch = r.arch.split(",")
              val eroles = arch.filter(_.startsWith("role."))
              val isPublic = arch.contains("public")

              // if the user matches one of the roles, it's ok
              allowed =
                isPublic || settings.user.isDefined &&
                  (eroles.isEmpty || eroles.exists(r => uroles.contains(r.substring(5))))

              reason = s"perm.roles=${eroles.mkString} user.roles= ${uroles.mkString} "

              if (!allowed) {
                // one more chance, look for masterRole/masterClient access to restricted rules
                val oauth = Website
                    .getRealmPropAsP (this.ctx.root.settings.realm.mkString, "oauth")
                    .map (_.getJsonStructure)
                val mr = oauth.flatMap(_.get("masterRole")).map(_.toString).mkString.trim

                if (mr.nonEmpty) allowed = uroles.contains(mr)
                reason = s"$reason oauth.mr=$mr "

                if (!allowed) {
                  val eclients = r.arch.split(",").filter(_.startsWith("client."))
                  val uclient = this.settings.user.flatMap(_.authClient).mkString

                  // rule contains client
                  if (uclient.nonEmpty) allowed = eclients.contains(uclient)
                  reason = s"$reason perm.clients=${eclients.mkString} user.client=${uclient} "

                  if (!allowed) {
                    val mc = oauth.flatMap(_.get("masterClient")).map(_.toString).mkString.trim.split(",")
                    reason = s"$reason oauth.mc=$mc "

                    // user comes from masterclient
                    if (mc.nonEmpty) allowed = mc contains uclient
                  }
                }
              }
            }

            if(!allowed) {
              // super admins can call APIs
              allowed = this.settings.user.exists(_.isAdmin)
            }

            if(! allowed) {
              val msg = s"AUTH FAILED for msg diesel.rest ! ($reason)"
//              throw new DieselException(s"AUTH FAILED for API diesel.rest ! ($reason)", Some(401))
              newNodes = newNodes :::
                  DomAst(
                    EError(msg, "Returning AUTH ERROR"), AstKinds.GENERATED) ::
                  DomAst(
                    EMsg("diesel.flow", "return", List(
                      P.fromSmartTypedValue("diesel.http.response.status", 401),
                      P.fromSmartTypedValue("payload", msg)
                    )).withPos(r.pos), AstKinds.GENERATED) ::
                  Nil
            }
          }

        if(allowed)  newNodes = newNodes ::: ruleDecomp(level, a, n, r, ctx)
        }
      }
    }

    /* any generated sub-messages go in sequence - important as many mocks and rules may apply
       side-effecting

       this applies across all rules and mocks decomposed, in sequence

       this will chain all generated messages, so each waits for predecessor

      we only apply default sync if no applicable $flow
     */
    if (findFlows(a).isEmpty && newNodes.size > 1) newNodes.drop(1).foldLeft(newNodes.head)((a, b) => {
      b.withPrereq(List(a.id))
    })

    // 3. executors

    // no mocks, let's try executing it
    // todo WHY - I run snaks even if rules fit - but not if mocked
    // todo now I run only if nothing else fits
    if (!mocked && !ruled && !settings.simMode) {
      // todo can I just take the first executor that works?
      Executors.withAll(_.filter { x =>
        // todo inconsistency: I am running rules if no mocks fit, so I should also run
        //  any executor ??? or only the isMocks???
        (true /*!settings.mockMode || x.isMock*/) && x._2.test(a, n)
      }.foreach { t =>
        val r = t._2
        mocked = true

        val news = try {

          // call the executor
          val xx = r.apply(n, None)

          // step 1: expand lists
          val yy = xx.flatMap(x => {
            x match {
              case l: List[_] => l
              case e@_ => List(e)
            }
          })

          yy.collect{
            // collect resulting values in the context as well
            case v@EVal(p) => {
              setSmartValueInContext(a, ctx, v)
              v
            }
            case p:P => {
              val v = EVal(p)
              this.setSmartValueInContext(a, ctx, v)
              v
            }
            case e@_ => e
          }.map{x =>
            DomAst.wrap(x, parentKind) withSpec(r)
          }
        } catch {
          case e: Throwable =>
            razie.Log.alarmThis(s"Exception from Executor ${r.name} for Msg: ${n.toString} : ", e)
            val p = EVal(new P(Diesel.PAYLOAD, e.getMessage, WTypes.wt.EXCEPTION).withValue(e, WTypes.wt.EXCEPTION))

            setSmartValueInContext(a, ctx, p.p)
            var res = List(DomAst(new EError("Exception:", e), AstKinds.ERROR), DomAst(p, AstKinds.ERROR))
            res
        }

        /* make any generated activities dependent so they run in sequence
        * todo how to hangle decomp to parallel? */
        if (n.ea != "ctx.set" && findFlows(a).isEmpty && news.size > 1) news.drop(1).foldLeft(news.head)((a, b) => {
//          if(!DomState.isDone(b.status) && !DomState.isDone(a.status)) // optimization - only add depy if needed
            b.withPrereq(List(a.id))
//          else
//            b
        })

        newNodes = newNodes ::: news
      })

    // 4. sketch

    // last ditch attempt, in sketch mode: if no mocks or rules, run the expects
    if (!mocked && settings.sketchMode) {
      // sketch messages - from AST and then dom, as the stories are not always told
      // when running as an API from REST (wrest) the story is collected in DOM not in AST
      (collectValues {
        case x: ExpectM if x.when.exists(_.test(a, n)) => x
      }.headOption
          orElse
          dom.moreElements.collectFirst {
            case x: ExpectM if x.when.exists(_.test(a, n)) => x
          }
          ).map { e =>
        mocked = true

        val spec = dom.moreElements.collectFirst {
          case x: EMsg if x.entity == e.m.cls && x.met == e.m.met => x
        }

        val newctx = mkLocalMsgContext(Some(n), n.attrs, ctx, a)
        val news = e.sketch(None)(newctx).map(x => DomAst(x, AstKinds.SKETCHED).withSpec(e))
        newNodes = newNodes ::: news
      }

      // sketch messages - from AST and then dom, as the stories are not always told
      // when running as an API from REST (wrest) the story is collected in DOM not in AST
      (collectValues {
        case x: ExpectV if x.when.exists(_.test(a, n)) => x
      }.headOption
          orElse
          dom.moreElements.collectFirst {
            case x: ExpectV if x.when.exists(_.test(a, n)) => x
          }
          ).foreach { e =>
        val newctx = new StaticECtx(n.attrs, Some(ctx), Some(a))
        val news = e.sketch(None)(newctx).map(x => EVal(x)).map(x => DomAst(x, AstKinds.SKETCHED).withSpec(e))
        mocked = true

        news.foreach { n =>
          evAppChildren(a, n)
        }
      }
    }

      // nothing matched ?
      // todo can't look at newNodes.isEmpty - some insert like TRACES etc - no decomp msg/vals
      if(!mocked && !ruled /*newNodes.isEmpty*/
      && !DO_THIS.equals(in.ea) && !DO_THAT.equals(in.ea)) {
        // change the nodes' color to warning and add an ignorable warning
        import EMsg._
        // todo this is bad because I change the message - is there impact to persistence of events and DomAsts ?
        a.value match {
          case m: EMsg => //findParent(a).foreach { parent =>
            a.value = m.copy(
              stype = s"${m.stype},$WARNING"
            ).copiedFrom(m)
//            parent.children.update(parent.children.indexWhere(_ eq initialA), a)
          //}
        }

        addNoRules(in, n, a) // nothing matched
      }
    }

    (newNodes, skippedNodes)
  }

  /** add the "no rules matched warning" */
  private def addNoRules(in:EMsg, n:EMsg,a:DomAst) = {
    // not for internal diesel messages - such as before/after/save etc
    val ms = n.entity + "." + n.met
    if(ms.equals(DieselMsg.ENGINE.DO_THIS) || ms.equals(DieselMsg.ENGINE.DO_THAT)) {
      val m = s"${in.ea} is reserved for if/else"
      val cfg = this.pages.map(_.specRef.wpath).mkString("\n")
      a.setKinds(AstKinds.TRACE)
      evAppChildren(a, DomAst(ETrace (m), AstKinds.VERBOSE))
    }
    else if(!ms.startsWith("diesel.")) {
      val m = s"No rules, mocks or executors match for the above ${in.ea} with this signature - verify your arguments!"
      val cfg = this.pages.map(_.specRef.wpath).mkString("\n")
      evAppChildren(a, DomAst(
        EWarning (
          m,
          s"Review your engine configuration (blender=${settings.blenderMode}, mocks=${settings.blenderMode}, drafts=${settings.blenderMode}, tags), " +
              s"spelling of messages or rule clauses / pattern matches\n$cfg",
          DieselMsg.ENGINE.ERR_NORULESMATCH),
        AstKinds.GENERATED
      ))

      // in strict mode, blow up...
      if(ctx.root.strict) throw new DieselExprException(m)
    }
  }

  /** reused - execute a rule */
  private def ruleDecomp (level: Int, a:DomAst, in:EMsg, r:ERule, parentCtx:ECtx) : List[DomAst] = {
    var result: List[DomAst] = Nil
    implicit val ctx: ECtx = new StaticECtx(in.attrs, Some(parentCtx), Some(a))

    val parents = new collection.mutable.HashMap[Int, DomAst]()

    val tailrec = (r.arch contains "tailrec")

    if (level >= maxLevels) {
      // if annotated with tail rec, we'll leave it
      if (findParentWith(a, _.tailrec).isDefined) {
        if(tailrec) {
          evAppChildren(a,
            DomAst(EInfo("Ignoring @tailrec when maxLevels reached"), AstKinds.DEBUG))
          trace("Ignoring @tailrec")
        }
      } else {
        evAppChildren(a,
          DomAst(
            TestResult(
              "fail: maxLevels!", "a.You have a recursive rule. use<tailrec> or set `diesel.levels(max=100)`"),
            "error"))
        return Nil
      }
    }

    //todo used to set kind to generated but parent makes more sense.getOrElse(AstKinds.GENERATED)
    val parentKind = a.kind match {
      case AstKinds.TRACE => a.kind
      case _ => AstKinds.GENERATED
    }

    // used to build trees from flat list with levels
    def addChild(x: Any, level: Int) = {

      // here 'cause i don't want it used elsewhere...
      def findParent(level: Int): DomAst = {
        if (level >= 0) parents.get(level).getOrElse(findParent(level - 1))
        else throw new DieselExprException("can't find parent in ruleDecomp - check your indentation levels!")
      }

      val ast = DomAst(x, AstKinds.NEXT).withSpec(r).withTailrec(tailrec)
      parents.put(level, ast)

      if (level == 0) Some(ast)
      else if (level > 0) {
        val parent = findParent(level - 1)
        parent.appendAllNoEvents(List(ast)) // todo I'm not creating events for persist for this
        None //child was consumed

        // the way leveled children work: when processing the parent message, they are REMOVED and re-added as
        // "generated" nodes
      } else {
        throw new DieselExprException("child level negative in ruleDecomp")
      }
    }

    var subRules = 0

    // generate each gen/map
    r.i.map { ri =>
      // find the spec of the generated message, to ref
      val spec = dom.moreElements.collectFirst {
        case x: EMsg if x.entity == ri.cls && x.met == ri.met => x
      }
      val KIND = if (parentKind != AstKinds.TRACE) AstKinds.kindOf(r.arch) else parentKind

      val generated = ri.apply(in, spec, r.pos, r.i.size > 1, r.arch)

      // defer evaluation if multiple messages generated - if just one message, evaluate values now
      val newMsgs = generated.collect {
        // hack: if only values then collect resulting values and dump message
        // only for size 1 because otherwise they may be in a sequence of messages and their value
        // may depend on previous messages and can't be evaluated now
        //
        case x: EMsg if x.entity == "" && x.met == "" && r.i.size == 1 => {
          // these go into the ctx as well
          appendVals(a, x.pos, Some(x), x.attrs, ctx, KIND)
          None
        }

        case x: EMsg if x.entity == "" && x.met == "" && r.i.size > 1 => {
          // can't execute now, but later
          Some(DomAst(ENext(x, "=>", None, true), AstKinds.NEXT).withSpec(r).withTailrec(tailrec))
        }

        // else collect message
        case x: EMsg => {
          subRules += 1
          if(r.i.size > 1)
            Some(DomAst(ENext(x, "=>"), AstKinds.NEXT).withSpec(r).withTailrec(tailrec))
          else
            Some(DomAst(x, KIND).withSpec(r).withTailrec(tailrec))
        }

        case x: ENext => {
          subRules += 1
          addChild(x, x.indentLevel)
        }

        case x: ENextPas => {
          addChild(x, x.indentLevel)
        }

        case x @ _ => {
          // vals and whatever other things
          subRules += 1
          Some(DomAst(x, KIND).withSpec(r).withTailrec(tailrec))
        }
      }.filter(_.isDefined)

      // if multiple results from a single map, default to sequence, i.e. make them dependent
      //        newMsgs.drop(1).foldLeft(newMsgs.headOption.flatMap(identity))((a,b)=> {
      //          Some(b.get.withPrereq(List(a.get.id)))
      //        })

      result = result ::: newMsgs.flatMap(_.toList)
    }

    // wrap in rule context push/pop
    // comment this if to allow local vars to propagate...

    // most likely got here with a LocalCtx ->
    // replace parent context with scoped or passthrough if just one PAS
    // this rule scope is just starting, so we have to decide what we'll use

    // I was going to allow single sets to go up, but NAH, they make it too random behaviour - all sets behave the same

    val oldCtx = a.getCtx.get.asInstanceOf[SimpleECtx]

    // if new node had a scope context, honor it
    val newParentCtx =
    // todo commented - see DomStream.setCtx - creator must be able to spec scopes
//      if (a.getMyOwnCtx.exists(_.isInstanceOf[ScopeECtx])) {
//        a.getMyOwnCtx
//      }
//      else
      a.getCtx.get.base.orElse(a.getCtx)

    val replacementCtx = new RuleScopeECtx(oldCtx.cur, newParentCtx, Some(a)).replacing(oldCtx)

    a.replaceCtx(replacementCtx)

    // if has a spec and exports params, export them
    in.spec.toList.filter(_.ret.size > 0).flatMap(_.ret).foreach { r =>
      val x = EMsg(
        "ctx.export",
        List(
          new P("toExport", r.name, WTypes.wt.STRING),
          new P(r.name, "", WTypes.wt.EMPTY, Some(AExprIdent(r.name)))
        )
      ).withArch(AstKinds.TRACE).withPos(in.pos)
      result = result ::: List(
        DomAst(ENext(x, "=>"), AstKinds.NEXT).withSpec(r)
      )
    }

    /* if multiple results from a single map, default to sequence, i.e. make them dependent

      this one applies to those generated from this rule only

      this will chain the children, so each waits for predecessor

      we only apply default sync if no applicable $flow
     */
    if (findFlows(a).isEmpty && result.size > 1) result.drop(1).foldLeft(result.head)((a, b) => {
      b.withPrereq(List(a.id))
    })

    result
  }

  /** if it's an internal engine message, execute it
    *
    * @param a
    * @param in
    * @param childrenIfAny children of the current node, if there were any - see call site
    * @param ctx
    * @return
    */
  private def expandEngineEMsg(a: DomAst, in: EMsg, childrenIfAny: List[DomAst])(implicit ctx: ECtx): (Boolean,
      List[DomAst], List[DomAst]) = {
    var skippedNodes = new ListBuffer[DomAst] // nodes skipped during this call collect here
    var newNodes = new ListBuffer[DomAst] // nodes skipped during this call collect here

    val ea = in.ea

    def skipNode(ast: DomAst, state: String) = {
      skippedNodes.append(ast)
      evChangeStatus(ast, state)
    }

    def addChild(parent: DomAst, ast: DomAst) = {
//      evAppChildren(parent, ast)
      newNodes.append(ast)
    }

    val res = {
      if (ea == DieselMsg.ENGINE.DIESEL_SUMMARY) {
        log("diesel.summary : " + in.attrs.mkString)

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_HEADING) {
        log("diesel.heading : " + ctx.get("desc").mkString)

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_STEP) {
        log("diesel.step : " + ctx.get("desc").mkString)

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_NOP) {
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_RUNSTORY) {
        val wpath = in.attrs.find(_.name == "wpath").map(_.calculatedValue).getOrElse {
          val name = in.attrs.find(_.name == "name").map(_.calculatedValue)
          val wp = s"${this.settings.realm.mkString}.Story:${name.mkString}"
          wp
        }

        import razie.wiki.model._

        // todo nice error if not found
        val story = WID.fromPath(wpath).flatMap(Wikis.find).toList

        if(story.isEmpty) {
          evAppChildren(a,
            DomAst(new EError(s"Could not find story to embed: " + wpath, ""), AstKinds.GENERATED)
          )
        }

        EnginePrep.addStoriesToAst(this, story, Some(a))

        // they're RECEIVED, should be GENERATED now so they can run...
//        a.childrenCol.foreach(_.kind = AstKinds.GENERATED)
//        a.childrenCol.head.childrenCol.foreach(_.kind = AstKinds.GENERATED)

        a.childrenCol.foreach(_.parent = None)
        newNodes.appendAll(a.childrenCol)
        a.childrenCol.clear() //

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_CLEANSTORY) { //========================

        if ((description contains "Guardian") && !settings.slaSet.contains(DieselSLASettings.VERBOSE)) {
          // parent node must be story
          var p = a.parent
          if (a.parent.exists(_.value.isInstanceOf[ENext])) {
            p = p.get.parent
          }

          // guardian-starts-story and guardian-ends-story no need no cleaning, wanna see what they done
          p.filter(x=> x.value.isInstanceOf[StoryNode] && {
            val wp = x.value.asInstanceOf[StoryNode].path.wpath
            wp != ("guardian-starts-story") &&
            wp != ("guardian-ends-story")
          }).foreach { p =>
            val sn = p.value.asInstanceOf[StoryNode]
            if (sn.calculateStats(p).failed == 0) {
              // if story completed and nothing failed, remove it from test report
              p.removeTestDetails()
            }
          }

          // also reset the maxExpands, so it works per story?
          this.curExpands = 0
        } else {
          val newD = DomAst(new EInfo(s"Skipped - not guardian OR verbose", ""), AstKinds.GENERATED)
          evAppChildren(a, newD)
        }

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_EXIT) { //========================

        Audit.logdb("DIESEL_EXIT", s"user ${settings.userId}")

        if (Services.config.isLocalhost) {
          evAppChildren(a, DomAst(new EInfo(s"Exiting in 5 sec...", ""), AstKinds.GENERATED))

          razie.Threads.fork {
            Thread.sleep(5000)
            System.exit(-1)
          }
        }

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_LOG) { //========================

        in.attrs.map(_.calculatedP).foreach { p =>
          clog << "diesel.log " + p.calculatedValue
        }

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_AUDIT) { //========================

        Audit.logdb("DIESEL_AUDIT", in.attrs.map(_.calculatedValue): _*)

        in.attrs.map(_.calculatedP).foreach { p =>
          clog << "diesel.audit " + p.calculatedValue
        }

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_CRASHAKKA) { //========================

        Audit.logdb("DIESEL_CRASHAKKA", s"user ${settings.userId}")

        if (Services.config.isLocalhost) {
          DieselAppContext.getActorSystem.terminate()
        }

        true

      } else if (
        ea == DieselMsg.ENGINE.DIESEL_RETURN ||
        ea == DieselMsg.ENGINE.DIESEL_FLOW_RETURN ||
        ea == DieselMsg.ENGINE.DIESEL_STORY_RETURN) { //========================

        // set all attributes in the root context
        in.attrs.map(_.calculatedP).foreach { p =>
          evAppChildren(a, DomAst(EVal(p)))
          setSmartValueInContext(a, a.getCtx.get.root, p) // set into root context, since we're returning
        }

        // payload must overwrite anything
        in.attrs.map(_.calculatedP).filter(_.name == Diesel.PAYLOAD).foreach { p =>
          evAppChildren(a, DomAst(EVal(p)))
          // set into scope context, since we're finishing this scope
          setSmartValueInContext(a, a.getCtx.get, p)
        }

        // story node prevents an entire test terminated because one story used diesel.return
        // 1. try to see if I'm inside a story - the top most node under the story,
        // if not, then the story and if not, then the ROOT

        // likewise, diesel.rest is a scope for return

        var scope = {
          (if (ea != DieselMsg.ENGINE.DIESEL_STORY_RETURN)
            // try to cancel just the message that used return
            findParentWith(a, _.parent.exists(_.value.isInstanceOf[StoryNode]))
          else None
          )
              .orElse(
                // skip rest of story
                findParentWith(a, _.value.isInstanceOf[StoryNode])
              )
              .orElse(
                findParentWith(a, x => x.value.isInstanceOf[EMsg] && x.value.asInstanceOf[EMsg].ea == DIESEL_REST)
              )
              .getOrElse(root)
        }

        // stop other children
        val skipped = scope.collect {
          case ast if (!DomState.isDone(ast.status)) => {
            if (DomState.inProgress(ast.status)) {
              skipNode(ast, DomState.CANCEL)
            } else {
              skipNode(ast, DomState.SKIPPED)
            }
          }
            ast
        }

        val newD = DomAst(new EInfo(s"Skipped ${skipped.size} nodes due to 'diesel.flow.return' ", ""), AstKinds.GENERATED)
        evAppChildren(a, newD)

        if (ea == DieselMsg.ENGINE.DIESEL_RETURN) {
          evAppChildren(a,
            DomAst(new EError(s"diesel.return is deprecated!! Use `diesel.flow.return` ", ""), AstKinds.GENERATED)
          )
        }

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_SCOPE_RETURN) { //========================

        // expand all spec vals
        in.attrs.map(_.calculatedP).foreach { p =>
          evAppChildren(a, DomAst(EVal(p)))
          // set into scope context, since we're finishing this scope
          setSmartValueInContext(a, a.getCtx.get.getScopeCtx, p)
        }

        // payload must overwrite anything
        in.attrs.map(_.calculatedP).filter(_.name == Diesel.PAYLOAD).foreach { p =>
          evAppChildren(a, DomAst(EVal(p)))
          // set into scope context, since we're finishing this scope
          setSmartValueInContext(a, a.getCtx.get, p)
        }

        // stop other children of scope
        val skipped = findScope(a).collect {
          case ast if (!DomState.isDone(ast.status)) => {
            if (DomState.inProgress(ast.status)) {
              skipNode(ast, DomState.CANCEL)
            } else {
              skipNode(ast, DomState.SKIPPED)
            }
          }
            ast
        }

        val newD = DomAst(new EInfo(s"Skipped ${skipped.size} nodes", ""), AstKinds.GENERATED)
        evAppChildren(a, newD)

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_RULE_RETURN) { //========================

        // expand all spec vals
        in.attrs.map(_.calculatedP).foreach { p =>
          evAppChildren(a, DomAst(EVal(p)))
          // set into scope context, since we're finishing this scope
          setSmartValueInContext(a, a.getCtx.get.getScopeCtx, p)
        }

        // stop other children of scope
        val rule = findParentWith(a, _.getMyOwnCtx.exists(_.isInstanceOf[RuleScopeECtx]))
        val toSkip = rule.orElse(Some(findScope(a))).collect {
          // first parent may be a ENext, see through
          case ast if ast.value.isInstanceOf[ENext] => findParent(ast)
          case ast@_ => Some(ast)
        }.flatten.toList.flatMap(_.children)

        var counter = 0
        toSkip.foreach { a =>
          a.collect {
            case ast if (!DomState.isDone(ast.status)) => {
              counter += 1
              if (DomState.inProgress(ast.status)) {
                skipNode(ast, DomState.CANCEL)
              } else {
                skipNode(ast, DomState.SKIPPED)
              }
              ast
            }
          }
        }

        val newD = DomAst(new EInfo(s"Skipped ${counter} nodes", ""), AstKinds.GENERATED)
        evAppChildren(a, newD)

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_THROW) { //========================

        // todo
        // exc has scope, exception, message and more stuff
        // default scope is global, other values: "scope" or "rest" or "local"

        // todo also while recursing see if htere's matching CATCH and stop skipping

        var msg = "diesel.thrown"
        var code: Option[Int] = Some(500) // default code

        // expand all spec vals
        in.attrs.map(_.calculatedP).foreach { p =>
          evAppChildren(a, DomAst(EVal(p)))
          setSmartValueInContext(a, a.getCtx.get.getScopeCtx, p) // set to scope directly, as we're exiting the others

          if (p.name == Diesel.PAYLOAD || p.name == "msg") {
            msg = p.currentStringValue
          } else if (p.name == DieselMsg.HTTP.STATUS) {
            code = Some(p.calculatedTypedValue.asLong.toInt)
          }
        }

        // now try to skip to a catch if any

        var caught = false
        val aLevel = findLevel(a)

        def isCatch(ast:DomAst, value:Any) =
          value.isInstanceOf[EMsg] &&
              value.asInstanceOf[EMsg].ea == DieselMsg.ENGINE.DIESEL_CATCH &&
              !DomState.isDone(ast.status)

        // stop other children
        // todo find cur scope, not parent
        findScope(a).collect {
          case ast@_ => Some(ast)
        }.flatten.toList.flatMap(_.children).foreach { ast =>
          if (
//            false &&
                !caught && isCatch(ast, ast.value)
          //&&
//            findLevel(ast) <= aLevel // only if it's higher level or sibbling
          // todo should i also check I don't know what, to make sure it was meant to catch me?
          // todo that it comes after ME
          // todo that it didn't catch something already?
          ) {
            val x = findLevel(ast)
            caught = true // stop when caught

            val old = ast.value.asInstanceOf[EMsg]

            // todo add info to the catch that it caught this
            // todo filter old attrs to not have values for those two?
            // also if there are more throws, does it collect exceptions or do we stop at first throw?
            ast.value = old.copy(
              attrs = old.attrs ++ List(
                P.fromSmartTypedValue("caught", true),
                P.fromSmartTypedValue("exception", msg)
              )
            ).copiedFrom(old)
            // todo record this change in state for engine recovery
          } else if (
//            false &&
                !caught &&
                (
                    ast.value.isInstanceOf[ENext] &&
                        isCatch(ast, ast.value.asInstanceOf[ENext].msg)
                    )
          //&&
//            findLevel(ast) <= aLevel // only if it's higher level or sibbling
          // todo should i also check I don't know what, to make sure it was meant to catch me?
          // todo that it comes after ME
          // todo that it didn't catch something already?
          ) {
            val x = findLevel(ast)
            caught = true // stop when caught

            val oldm = ast.value.asInstanceOf[ENext].msg
            val olde = ast.value.asInstanceOf[ENext]

            // todo add info to the catch that it caught this
            // todo filter old attrs to not have values for those two?
            // also if there are more throws, does it collect exceptions or do we stop at first throw?
            val newm = oldm.copy(
              attrs = oldm.attrs ++ List(
                P.fromSmartTypedValue("caught", true),
                P.fromSmartTypedValue("exception", msg)
              )
            ).copiedFrom(oldm)

            ast.value = olde.copy(msg = newm).copiedFrom(olde)

          } else if (!DomState.isDone(ast.status) && !caught) {
            // skip all other sibblings...
            skipNode(ast, DomState.SKIPPED)
          }
        }

        // todo then set payload to exception
        val ex = new DieselException(msg, code)

        val p = EVal(new P(Diesel.PAYLOAD, msg, WTypes.wt.EXCEPTION).withValue(ex, WTypes.wt.EXCEPTION))
        setSmartValueInContext(a, a.getCtx.get.getScopeCtx, p.p) // set straight to scope

        val newD = DomAst(new EError("Exception:", ex), AstKinds.ERROR)
        evAppChildren(a, newD)

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_ERROR) { //========================

        val newD = DomAst(new EError("diesel.error", in.attrs.map(_.calculatedValue).mkString),
          AstKinds.ERROR)
        evAppChildren(a, newD)
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_REALM_READY) { //========================

        val realm = ctx.getRequired("realm")
        val r = WikiReactors(realm)
        if (!r.ready.isCompleted) WikiReactors(realm).ready.success(true)
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_TRY) { //========================

        // rudimentary TRY scope for catch.
        // normally we'd have CATCH apply to the enclosing scope, but we don't have a good EEScope

        val newD = DomAst(new EScope("diesel.try:", Some(in), ""), AstKinds.GENERATED)
        evAppChildren(a, newD)
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_CATCH) { //========================

        // todo handle
        // for the current scope, stop prpag exc and handle if matching
        // exc has attrs: exception and message and then more crap
        // catch must match

        // for now - let's just handle scope exceptions

        var inScope = false
        var done = false

        // 1. am I in scope?
        findScope(a).collect {
          case ast@_ => Some(ast)
        }.flatten.toList.flatMap(_.children).foreach { ast =>
          if (!done) {
            ast.collect({
              // todo more generic scope ident
              // the idea is that catch works in a scope, not just inside try
              case x if x.value.isInstanceOf[EMsg] && x.value.asInstanceOf[EMsg].ea == DIESEL_TRY => {
                inScope = true
              }
            })

            if (ast eq a) {
              done = true // found myself - stop - good for seq todo handle par
            }
          }
        }

        done = false

        var handledError = false

        val exc = new ListBuffer[P]()

        findScope(a).collect {
          case ast@_ => Some(ast)
        }.flatten.toList.flatMap(_.children).foreach { ast =>
          // find any EError and mark it "handled"
          if (!done) {
            ast.collect({
              case x if x.value.isInstanceOf[EError] && !x.value.asInstanceOf[EError].handled /* && inScope */ => {
                val ee = x.value.asInstanceOf[EError]
                ee.handled = true
                val newD = DomAst(new EInfo("caught:" + ee.msg, ""), AstKinds.GENERATED)
                evAppChildren(a, newD)
                handledError = true

                val p = P.fromSmartTypedValue("exception", Map(
                  "code" -> ee.code,
                  "message" -> ee.msg,
                  "details" -> ee.details
                ))

                exc.append(p)

                val v = DomAst(new EVal(p), AstKinds.GENERATED)
                evAppChildren(a, v)
                setSmartValueInContext(a, ctx.getScopeCtx, p)
              }
            })

            if (ast eq a) {
              done = true // found myself - stop - good for seq todo handle par
            }
          }
        }

        // todo if DONE vs CAUGHT - done means just exception found, no "throw" found

        // IF diesel.throw was used, it will have this attribute in case something was "caught"
        val caught = in.attrs.exists(p => p.name == "caught" && p.calculatedTypedValue.asBoolean == true)

        // skip other children if no exception
        if (!(handledError || caught)) childrenIfAny.foreach { ast =>
          if (!DomState.isDone(ast.status)) {
            ast.status = DomState.SKIPPED
            // todo this bombs if the children are not in tree yet...
//          evChangeStatus(ast, DomState.SKIPPED)
          }
        } else {
          // only set this if children will be processed, otherwise ignore
          val p = P.fromSmartTypedValue("exceptions", exc.toList)
          val v = DomAst(new EVal(p), AstKinds.GENERATED)
          evAppChildren(a, v)
          setSmartValueInContext(a, ctx.getScopeCtx, p)
        }

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_ASSERT) {

        var errs: List[P] = Nil

        // evaluate all boolean parms
        val cp = in.attrs.map(_.calculatedP)
        val bcp = cp.filter(_.isOfType(WTypes.wt.BOOLEAN)).map { p =>
          val res = p.value.get.asBoolean
          if (!res) errs = p :: errs
          res
        }
        val res = bcp.foldRight(true)((a, b) => a && b)

        val newD =
          if (res && bcp.size > 0)
            List(DomAst(new EInfo("assert OK"), AstKinds.GENERATED))
          else {
            val m = errs.mkString
            val resp = cp
                .find(_.name == DieselMsg.HTTP.RESPONSE)
                .getOrElse {
                  P.fromSmartTypedValue(DieselMsg.HTTP.RESPONSE, "Assert failed: " + m)
                }
            val code = cp
                .find(_.name == DieselMsg.HTTP.STATUS)
                .getOrElse {
                  P.fromSmartTypedValue(DieselMsg.HTTP.STATUS, 500)
                }

            List(
              DomAst(EWarning("Assert failed: " + m)),
              DomAst(new EMsg("diesel.story", "return",
                resp :: code :: cp.filter(!_.isOfType(WTypes.wt.BOOLEAN))
              ),
                AstKinds.GENERATED))
          }
        newD.foreach(addChild(a, _))
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_MSG || ea == DieselMsg.ENGINE.DIESEL_CALL) { //========================

        val EMsg.REGEX(e, m) = ctx.getRequired("msg")
        val nat = in.attrs.filter(e => e.name != "msg")

        addChild(a, DomAst(EMsg(e, m, nat).withPos(in.pos), AstKinds.GENERATED))
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_PROGRESS) { //========================

        val pc = ctx.getRequired("current")
        val pt = ctx.getRequired("total")
        val ps = ctx.get("status").getOrElse("")

        this.engineProgress.set(pc.toInt, pt.toInt, ps)
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_LATER) { //========================

//      // send new message async later at the root level
//      val EMsg.REGEX(e, m) = ctx.getRequired("msg")
//      val nat = in.attrs.filter(e => !Array("msg").contains(e.name))
//
//      evAppChildren(findParent(findParent(a).get).get, DomAst(EMsg(e, m, nat), AstKinds.GENERATED))
        false

      } else if (ea == DieselMsg.ENGINE.DIESEL_LEVELS) { //========================

        engine.maxLevels = ctx.getRequired("max").toInt
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_PONG) { //========================

        // expand vals
        // todo why need this new context?
        val nctx = mkMsgContext(Some(in), calcMsg(a, in)(ctx).attrs, ctx, a)

        val parentNode = razie.wiki.model.DCNode (nctx.getRequired("parentNode"))
        val parentId = nctx.getRequired("parentId")
        val targetId = nctx.getRequired("targetId")
        val level = nctx.getRequired("level").toInt

        // this passes payload too
        notifyParent (a, DomAssetRef(DomRefs.CAT_DIESEL_ENGINE, parentId, None, Option(targetId)).withNode(parentNode), level)
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_VALS) {

        // expand all spec vals
        dom.moreElements.collect {
          case v: EVal => {
            // clone so nobody changes the calculated value of p
            val newv = v.copy(p = v.p.copy().copyValueFrom(v.p)).copyFrom(v)
            evAppChildren(a, DomAst(newv, AstKinds.VERBOSE))
            // do not calculate here - keep them more like exprs .calculatedP)

            // todo this causes all kinds of weird issues

//          setSmartValueInContext(a, this.ctx, newv.p)

//          this.engine.ctx.put(newv.p) // do not calculate p, just set it at root level
            a.getCtx.get.getScopeCtx.put(newv.p) // do not calculate p, just set it at root level
          }
        }

        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_WARNINGS) { //========================

        // expand all domain warnings
        dom.moreElements.collect {
          case v: EWarning => {
            evAppChildren(a, DomAst(v, AstKinds.DEBUG))
          }
        }

        true

      } else if (ea == DieselMsg.DIESEL_REQUIRE) { //========================

        // find parent generating rule
        val parent: Option[EMsg] = a.parent.filter(_.value.isInstanceOf[ENext]).map(_.value.asInstanceOf[ENext].msg)

        // find who wasn't sent in, i.e. has no value in context
        val undefined = parent.toList.flatMap(_.attrs).filter(x => !in.attrs.exists(_.name == x.name))

        if(undefined.size > 0)
          throw new DieselExprException("Missing values for: " + undefined.map(_.name).mkString(","))
        else
          evAppChildren(a, DomAst(EInfo("All required values are present: " + in.attrs.map(_.name).mkString(",")), AstKinds.GENERATED))

        true

      } else if (ea == DieselMsg.DOM.META) { //========================

        // todo move to a EEDieselDom

        val cn = ctx.getRequired("className")

        val c = this.dom.classes.get(cn)

        val v = c.map {cls=>
          // todo caching of the json format per class?
          EVal(P.fromSmartTypedValue(Diesel.PAYLOAD, cls.toj))
        }.getOrElse {
          evAppChildren(a, DomAst(EWarning(s"Class not found in domain: $cn", cn)))
          EVal(P.undefined(Diesel.PAYLOAD))
        }

        evAppChildren(a, DomAst(v, AstKinds.TRACE))
        setSmartValueInContext(a, ctx, v.p)

        true

      } else if (ea == DieselMsg.ENGINE.STRICT) { //========================

        val p = in.attrs.headOption.filter(_.name == "strict").map(_.calculatedTypedValue.asBoolean).getOrElse(true)
        ctx.root.strict = p
        evAppChildren(a, DomAst(EInfo(s"strict is $p"), AstKinds.TRACE))
        true

      } else if (ea == DieselMsg.ENGINE.NON_STRICT) { //========================

        ctx.root.strict = false
        evAppChildren(a, DomAst(EInfo("strict is false"), AstKinds.TRACE))
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_SYNC) { //========================

        // turn the engine sync
        ctx.root.engine.map(_.synchronous = true)
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_ASYNC) { //========================

        // turn the engine async
        ctx.root.engine.map(_.synchronous = false)
        true

      } else if (ea == DieselMsg.SCOPE.DIESEL_PUSH) { //========================

        // todo use the DomAst ctx now, not the current
//      this.ctx = new ScopeECtx(Nil, Some(this.ctx), Some(a))
        true

      } else if (ea == DieselMsg.SCOPE.DIESEL_POP) { //========================

        // todo use the DomAst ctx now, not the current
        // find the closest scope and pop it
//      var sc: Option[ECtx] = Some(this.ctx)
//
//      while (sc.isDefined && !sc.exists(p => p.isInstanceOf[ScopeECtx])) {
//        sc = sc.get.base
//      }
//
//      if (sc.isDefined) {
//        this.ctx = this.ctx.base.get
//      } else {
//        evAppChildren(a, DomAst(EError("trying to pop a non-scope...", in.toString)))
//      }

        true

      } else if (ea == DieselMsg.SCOPE.RULE_PUSH) { //========================

        this.ctx = new RuleScopeECtx(Nil, Some(this.ctx), Some(a))
        true

      } else if (ea == DieselMsg.SCOPE.RULE_POP) { //========================

        assert(this.ctx.isInstanceOf[RuleScopeECtx])
        this.ctx = this.ctx.base.get
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_ENG_DEBUG) {

        val s = this.settings.toJson
        val c = this.ctx.toString
        val e = this.toString
        evAppChildren(a, DomAst(EInfo("settings", js.tojsons(s)), AstKinds.DEBUG))
        evAppChildren(a, DomAst(EInfo("ctx", c), AstKinds.DEBUG))
        evAppChildren(a, DomAst(EInfo("engine", e), AstKinds.DEBUG))
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_ENG_PAUSE) {

        // set the engine to pause mode and start stashing message until continue

        DieselAppContext ! DEPause(this.id)
        evAppChildren(a, DomAst(EInfo("Paused..."), AstKinds.DEBUG))
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_ENG_CONTINUE) {

        // push all stashed messages

        DieselAppContext ! DEContinue(this.id)
        evAppChildren(a, DomAst(EInfo("Continue..."), AstKinds.DEBUG))
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_ENG_PLAY) {

        // set the engine to pause mode and start stashing message until continue

        DieselAppContext ! DEPlay(this.id)
        evAppChildren(a, DomAst(EInfo("Paused..."), AstKinds.DEBUG))
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_ENG_CANCEL) {

        // cancel another engine
        val p = in.attrs.find(_.name == "id").map(_.calculatedTypedValue.asString)
        val r = in.attrs.find(_.name == "reason").map(_.calculatedTypedValue.asString).mkString

        p.map {id=>
          DieselAppContext ! DECancel(id, r, this.id, this.description.take(1000))
          evAppChildren(a, DomAst(EInfo(s"Sent cancel to id=$id"), AstKinds.DEBUG))
        }.getOrElse {
          evAppChildren(a, DomAst(EError("engineId argument missing!"), AstKinds.ERROR))
        }
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_ENG_SET) { //========================

        // todo move this to an executor

        // NOTE: this may be a security risk - they can set trust and stuff -  limit to only some parms
        in.attrs.foreach { p =>
          val v = p.currentStringValue
          p.name match {

            case "diesel.engine.settings.collectCount" | "collectCount" => {
              this.settings.collectCount = Option(v.toInt)
              // if it has collect settings there must be a group
              if (settings.collectGroup.isEmpty) {
                settings.collectGroup = Some(
                  description) // set it to desc just to be sure - it should be reset here as well
              }
            }

            case "diesel.engine.settings.collectGroup" | "collectGroup" => {
              this.settings.collectGroup = Option(v)
            }

            case "diesel.engine.maxLevels" | "maxLevels" => {
              // todo validate trusted realm
              engine.maxLevels = v.toInt
            }

            case "diesel.engine.maxExpands" | "maxExpands" => {
              // todo validate trusted realm
              engine.maxExpands = v.toInt
            }

            case "diesel.engine.keepIterations" | "keepIterations" => {
              // todo validate trusted realm
              engine.keepIterations = v.toInt
            }

            case "diesel.engine.keepSummaries" | "keepSummaries" => {
              // todo validate trusted realm
              engine.keepSummaries = v.toInt
            }
          }
        }

        evAppChildren(a,
          DomAst(EInfo(s"collect settings updated ${settings.collectCount} - ${settings.collectGroup}"),
            AstKinds.TRACE))

        true

      } else if (ea == "diesel.branch") { // nothing
        true

      } else if (ea == DieselMsg.REALM.REALM_SET) { //========================

        // you can do either props={a:1}
        val props = in.attrs
            .filter(_.name == "props")
            .filter(_.isOfType(WTypes.wt.JSON))
            .flatMap(_.calculatedTypedValue.asJson.toList)
            .map(t => P.fromSmartTypedValue(t._1, t._2))

        // todo move this to an executor
        // todo this may be a security risk - they can set trust and stuff -  limit to only some parms?
        (props ::: in.attrs.filter(_.name != "props")).foreach { p =>
          val r = this.settings.realm
          if (r.isEmpty) evAppChildren(a, DomAst(EError("realm not defined...???"), AstKinds.ERROR))
          else {
            r.foreach(Website.putRealmProps(_, p.name, p.calculatedP))
            val temp = p.calculatedValue
            r.flatMap(Website.forRealm).map(_.put(p.name, temp))
            evAppChildren(a, DomAst(EInfo(s"updated ${p.name} to ${temp.toString.take(200)}"), AstKinds.VERBOSE))
          }
        }
        true

      } else if (ea == DieselMsg.REALM.EVENTS_SET) { //========================

        // you can do either props={a:1}
        val event = in.attrs
            .filter(_.name == "event")
            .filter(_.isOfType(WTypes.wt.JSON))
            .flatMap(_.calculatedTypedValue.asJson)

        val events = event :: in.attrs
            .filter(_.name == "events")
            .filter(_.isOfType(WTypes.wt.ARRAY))
            .flatMap(_.calculatedTypedValue.asArray)

        // todo move this to an executor
        // todo this may be a security risk
        // todo limit no of events per realm
        if(events.size > 0) {
          val r = this.settings.realm
          if (r.isEmpty) evAppChildren(a, DomAst(EError("realm not defined...???"), AstKinds.ERROR))
          else {
            r.foreach(Website.putRealmEvents(_, events))
            evAppChildren(a, DomAst(EInfo(s"added ${events.size} events..."), AstKinds.VERBOSE))
          }
        }
        true

      } else if (ea == DieselMsg.ENGINE.DIESEL_MAP) { //========================

        // expanding diesel.map: make an item for each item and copy all children under the item

        // TODO problem is the indented children are evaludated and added AFTER this :(

        // save children and delete
        val parent = findParent(a).flatMap(findParent)
        val children = a.children
        a.childrenCol.clear()

        var info: List[Any] = Nil

        info = EInfo(s"engine msg") :: info

        // l can be a constant with another parm name OR the actual array
        val list = {
          val l = ctx.getRequiredp(Diesel.PAYLOAD).calculatedP
          if (l.isOfType(WTypes.wt.ARRAY)) {
            l
          } else if (l.isOfType(WTypes.wt.STRING)) {
            ctx.getRequiredp(l.currentStringValue)
          } else {
            info = EWarning(s"Can't source input list - what type is it? ${l}") :: info
            new P("", "", WTypes.wt.UNDEFINED) //throw new IllegalArgumentException(s"Can't source input list: $ctxList")
          }
        }

        razie.js.parse(s"{ list : ${list.currentStringValue} }").apply("list") match {

          case l: collection.Seq[Any] => {
            // passing any other parameters that were given to foreach

            l.map { item: Any =>
              // for each item in list, create message
              val itemP = P.fromTypedValue(Diesel.PAYLOAD, item)
              val m = EMsg("diesel", "mapItem", itemP :: Nil)
              val ast = DomAst(m, AstKinds.GENERATED)
              evAppChildren(a, ast)

              // copy children under each item
              // TODO problem is the indented children are evaludated and added AFTER this :(
//            children.foreach { c =>
//              evAppChildren(ast, DomAst(c.value, AstKinds.GENERATED))
//            }
            }.toList
          }

          case x@_ => {
            List(EError("value to iterate on was not a list", x.getClass.getName) :: info)
          }

        }

        info.map { x =>
          evAppChildren(a, DomAst(x, AstKinds.TRACE))
        }

        // expand all spec vals
        dom.moreElements.collect {
          case v: EVal => {
            evAppChildren(a, DomAst(v, AstKinds.TRACE))
            setSmartValueInContext(a, this.ctx, v.p)
          }
        }

        true

      } else {

        false
      }
    }

    (res, skippedNodes.toList, newNodes.toList)
  }

  // if the message attrs were expressions, calculate their values
  private def calcMsg(a: DomAst, in: EMsg)(implicit ctx: ECtx): EMsg = {
    val calcAttrs = in.attrs.flatMap { p =>
      // this flattening is a duplicate of EMap.sourceAttrs but required for $send which is not calculated...
      // flattening objects first
      if (p.expr.exists { e =>
        e.isInstanceOf[AExprIdent] &&
            e.asInstanceOf[AExprIdent].rest.size > 0 &&
            e.asInstanceOf[AExprIdent].rest.last.name.equals("asAttrs")
      }) {
        p :: flattenJson(p)
      } else if (p.name.endsWith(".asAttrs")) {
        // we have to resolve it here and flatten it
        val ap = new SimpleExprParser().parseIdent(p.name).map(_.dropLast)
        p :: flattenJson(p.copy(expr = ap).calculatedP)
      } else {
        List(p.calculatedP) // only calculate if not already calculated =likely in a different context=
      }
    }

    // add trace info for undefined parms
    calcAttrs
        .filter(_.ttype == WTypes.UNDEFINED)
        .foreach(attr => {
          evAppChildren(a, DomAst(EInfo(s"""UNDEFINED parm $attr"""), AstKinds.DEBUG))
        })

    in.copy(
      attrs = calcAttrs.filter(_.ttype != WTypes.UNDEFINED) // an undefined parm is the same as not passed in !!
    ).copiedFrom(in)
  }

  /** expand a single message and rep - called by engine, results in engine processing */
  private def expandEMsgAndRep(a: DomAst, in: EMsg, recurse: Boolean, level: Int, parentCtx:ECtx) : List[DEMsg] = {
    val (newNodes, skippedNodes) = expandEMsg(a, in, recurse, level, parentCtx)

    // analyze the new messages and return

    // this is what makes this synchronous behaviour - it reps itself as opposed to waiting for an async DEReply
    rep(a, recurse, level, newNodes) //::: skippedNodes.flatMap(freeDepys)
  }

  /** test epected message */
  private def expandExpectM(a: DomAst, e: ExpectM) : Unit = {
    val cole = new MatchCollector()

    implicit val ctx = a.getCtx.get

    Try {

      var targets = e.target.map(List(_)).getOrElse(if (e.when.isDefined) {
        // find generated messages that should be tested
        root.collect {
          case d@DomAst(n: EMsg, k, _, _) if e.when.exists(_.test(a, n)) => d
        }
      } else List(root))

      if (targets.size > 0) {
        // todo look at all possible targets - will need to create a matchCollector per etc
        targets.head.collect {
          case d@DomAst(n: EMsg, k, _, _) if AstKinds.isGenerated(k) =>
            cole.newMatch(d)
            if (e.m.test(a, n, Some(cole)))
              evAppChildren(a, DomAst(TestResult("ok").withTarget(e), AstKinds.TRACE).withSpec(e))
        }

        cole.done

        // if it matched some Msg then highlight it there
        if (cole.highestMatching.exists(c =>
          c.score >= 2 &&
              c.diffs.values.nonEmpty &&
              c.x.isInstanceOf[DomAst] &&
              c.x.asInstanceOf[DomAst].value.isInstanceOf[EMsg]
        )) {
          val c = cole.highestMatching.get
          val d = c.x.asInstanceOf[DomAst]
          val m = d.value.asInstanceOf[EMsg]
          val s = c.diffs.values.map(_._2).toList.map(
            x => s"""<span style="color:red">${htmlValue(x.toString)}</span>""").mkString(",")
          d.moreDetails = d.moreDetails + label("expected", "danger") + " " + s
        }

        // did some rules succeed?
        if (a.children.isEmpty) {
          // oops - add test failure
          evAppChildren(a, DomAst(
            TestResult(
              "fail",
              "",
              label("found", "warning") + " " +
                  cole.highestMatching.map(
                    _.diffs.values.map(_._1).toList.map(x => s"""<span style="color:red">$x</span>""").mkString(
                      ",")).mkString
            ).withTarget(e),
            AstKinds.TEST
          ).withSpec(e))
        }
      }

  }.recover {
    case t:Throwable => {

      razie.Log.error("while decompose em()", t)

      // oops - add test failure
      evAppChildren(a, DomAst(
        TestResult(
          "fail",
          "",
          cole.toHtml
        ).withTarget(e),
        AstKinds.TEST
      ).withSpec(e))

      evAppChildren(a, DomAst(new EError("Exception", t), AstKinds.ERROR).withStatus(DomState.DONE))
    }
  }.get
  }

  /** test epected values */
  private def expandExpectV(a: DomAst, e: ExpectV) : Unit = {
    val cole = new MatchCollector()

    Try {

    // identify sub-trees that it applies to

    val subtrees = e.target.map(List(_)).getOrElse(if (e.when.isDefined) {
      // find generated messages that should be tested
      // todo should also work if following expressions, not just messags - not a big deal but usability
      root.collect {
        case d@DomAst(n: EMsg, k, _, _) if e.when.exists(_.test(a, n)(a.getCtx.map(_.getScopeCtx).getOrElse(this.ctx))) => d
          // todo should we test cond in root context OR in local story scope
      }
    } else List(root))

    if (subtrees.size > 0) {
      // todo look at all possible targets - will need to create a matchCollector per etc

      subtrees.foreach { aTarget =>

        // collecting all Values created in the sub-tree
        var vvals = aTarget.collect {
          case d@DomAst(n: EVal, k, _, _) if AstKinds.isGenerated(k) => d
        }

        // reverse (so last overwrites first) and distinct (so multiple payloads don't act funny)
        vvals = vvals.reverse.groupBy(_.value.asInstanceOf[EVal].p.name).values.toList.map(_.head)
        val values = vvals.map(_.value.asInstanceOf[EVal].p)

        // bug: if the target had (x=y) and produced value x then newctx would overwrite the newer x, so
        // we're overwriting it again here by putting values in front of attrs

        // new evaluation context - include the message's values in its context
        if (!aTarget.value.isInstanceOf[EMsg]) {
          throw new DieselExprException("$expect must follow a $send...")
        }

        // we base off the parent scope (story scope context)
        // todo should we base off the target scope and include the target's internal parameters?
//        val newctx = new StaticECtx(
//          values ::: aTarget.value.asInstanceOf[EMsg].attrs.filter(b => !values.exists(a => a.name == b.name)),
//          values ::: aTarget.value.asInstanceOf[EMsg].attrs,
//          aTarget.getCtx,
//          Some(aTarget))

        // include target's attrs but not to overwrite context
//        val newctx = new StaticECtx(
//          values ::: aTarget.value.asInstanceOf[EMsg].attrs.filter(b => !values.exists(a => a.name == b.name)),
//          aTarget.getCtx,
//          Some(aTarget))

        // include target's attrs but not to overwrite context
        val newctx = new StaticECtx(
          values ::: aTarget.value.asInstanceOf[EMsg].attrs.filter(b => !values.exists(a => a.name == b.name)),
          Some(a.getCtx.get.getScopeCtx),
//          a.getCtx,
          Some(aTarget))


        // start checking now

        if (!aTarget.value.isInstanceOf[EMsg]) {
          // wtf did we just target?
          a append DomAst(
            TestResult("fail", "Target not a message - did something run?").withTarget(e),
            AstKinds.TEST
          ).withSpec(e)
        } else if (vvals.size > 0 && !e.applicable(a, values)(newctx)) {
          // n/a - had a guard and guard not met
          a append DomAst(
            TestResult("n/a", "$if condition not met").withTarget(e),
            AstKinds.TEST
          ).withSpec(e)
        } else if (vvals.size > 0 && e.test(a, values, Some(cole), vvals)(newctx)) {
          // test ok
          a append DomAst(
            TestResult("ok").withTarget(e),
            AstKinds.TRACE
          ).withSpec(e)
        } else if (vvals.size == 0 && !e.applicable(a, values)(newctx)) {
          // targeted tree generated no values, so this is a global state condition
          // n/a - had a guard and guard not met
          a append DomAst(
            TestResult("n/a", "$if condition not met").withTarget(e),
            AstKinds.TEST
          ).withSpec(e)
        } else if (vvals.size == 0 && e.test(a, Nil, Some(cole), vvals)(newctx)) {
          // targeted tree generated no values, so this is a global state condition

          a append DomAst(
            TestResult("ok").withTarget(e),
            AstKinds.TRACE
          ).withSpec(e)
        } else
        //if no rules succeeded and there were vals, collect the misses
          values.map(v => cole.missed(v.name))
      }

      cole.done

      // did some rules succeed?
      if (a.children.isEmpty) {
        // oops - add test failure
        a append DomAst(
          TestResult(
            "fail",
            "",
            cole.toHtml
          ).withTarget(e),
          AstKinds.TEST
        ).withSpec(e)
      }

      // if it matched some name then highlight it there
      // any match means a name matched ?
      if (cole.highestMatching.exists(c =>
        c.score >= 0 &&
          c.diffs.values.nonEmpty &&
          c.x.isInstanceOf[DomAst] &&
          c.x.asInstanceOf[DomAst].value.isInstanceOf[EVal]
      )) {
        val c = cole.highestMatching.get
        val d = c.x.asInstanceOf[DomAst]
        val m = d.value.asInstanceOf[EVal]
        val s = c.diffs.values.map(_._2).toList.map(x => s"""<span style="color:red">${htmlValue(x.toString)}</span>""").mkString(",")
        d.moreDetails = d.moreDetails + label("expected", "danger") + " " + s // don't do htmlValue(s) - it has purposeful <span> etc
      }

      // did some rules succeed?
      if (a.children.isEmpty) {
        // oops - add test failure
        a append DomAst(
          TestResult(
            "fail",
            "",
            label("found", "warning") + " " +
                cole.highestMatching.map(_.diffs.values.map(_._1).toList.map(
                  x => s"""<span style="color:red">${htmlValue(x.toString)}</span>""").mkString(",")).mkString
          ).withTarget(e),
          AstKinds.TEST
        ).withSpec(e)
      }
    }

    }.recover {
      case t:Throwable => {

        log(s"while decompose ExpectV: $e", t)

        // oops - add test failure
        a append DomAst(
          TestResult(
            "fail",
            "Exception: " + t.getClass.getSimpleName + ": " + t.getMessage,
            cole.toHtml
          ).withTarget(e),
          AstKinds.TEST
        ).withSpec(e)

        a append DomAst(new EError("Exception", t), AstKinds.ERROR).withStatus(DomState.DONE)
      }
    }.get
  }

  private def expandExpectAssert(a: DomAst, e: ExpectAssert) : Unit = {
    val cole = new MatchCollector()

    // identify sub-trees that it applies to

    val subtrees = e.target.map(List(_)).getOrElse(if (e.when.isDefined) {
      // find generated messages that should be tested
      root.collect {
        case d@DomAst(n: EMsg, k, _, _) if e.when.exists(_.test(a, n)(a.getCtx.map(_.getScopeCtx).getOrElse(this.ctx))) => d
      }
    } else List(root))

    if (subtrees.size > 0) {
      // todo look at all possible targets - will need to create a matchCollector per etc

      subtrees.foreach {n=>
        val vvals = n.collect {
          case d@DomAst(n: EVal, k, _, _) if AstKinds.isGenerated(k) => d
        }

        // include the message's values in its context
        val newctx = new StaticECtx(n.value.asInstanceOf[EMsg].attrs, Some(ctx), Some(n))

        val values = vvals.map(_.value.asInstanceOf[EVal].p)

        if (vvals.size > 0 && e.test(a, values, Some(cole), vvals)(newctx))
          a append DomAst(
            TestResult("ok").withTarget(e),
            AstKinds.TRACE
          ).withSpec(e)
        else
        //if no rules succeeded and there were vals, collect the misses
          values.map(v => cole.missed(v.name))
    }

      cole.done

      // did some rules succeed?
      if (a.children.isEmpty) {
        // oops - add test failure
        a append DomAst(
          TestResult(
            "fail",
            cole.toHtml
          ).withTarget(e),
          AstKinds.TEST
        ).withSpec(e)
      }

      // did some rules succeed?
      if (a.children.isEmpty) {
        // oops - add test failure
        a.append(DomAst(
          TestResult(
            "fail",
            label("found", "warning") + " " +
                cole.highestMatching.map(_.diffs.values.map(_._1).toList.map(
                  x => s"""<span style="color:red">${htmlValue(x.toString)}</span>""").mkString(",")).mkString
          ).withTarget(e),
          AstKinds.TEST
        ).withSpec(e))
      }
    }
  }

}


