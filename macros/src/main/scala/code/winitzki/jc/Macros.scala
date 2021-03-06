package code.winitzki.jc

import scala.collection.mutable
import scala.language.experimental.macros
import scala.reflect.macros._
import scala.reflect.NameTransformer.LOCAL_SUFFIX_STRING
import JoinRun._
import JoinRunUtils._

import scala.annotation.tailrec

object Macros {

  type theContext = blackbox.Context

  private[jc] def rawTree(x: Any): String = macro rawTreeImpl

  def rawTreeImpl(c: theContext)(x: c.Expr[Any]) = {
    import c.universe._
    val result = showRaw(x.tree)
    q"$result"
  }

  private[jc] def getName: String = macro getNameImpl

  private def getEnclosingName(c: theContext): String =
    c.internal.enclosingOwner.name.decodedName.toString
      .stripSuffix(LOCAL_SUFFIX_STRING).stripSuffix("$lzy")

  def getNameImpl(c: theContext): c.Expr[String] = {
    import c.universe._

    val s = getEnclosingName(c)

    c.Expr[String](q"$s")
  }

  def m[T]: M[T] = macro mImpl[T]

  def mImpl[T: c.WeakTypeTag](c: theContext): c.Expr[M[T]] = {
    import c.universe._
    val s = getEnclosingName(c)

    val t = c.weakTypeOf[T]

    c.Expr[M[T]](q"new M[$t]($s)")
  }

  def b[T, R]: B[T, R] = macro bImpl[T, R]

  def bImpl[T: c.WeakTypeTag, R: c.WeakTypeTag](c: blackbox.Context): c.Expr[B[T, R]] = {
    import c.universe._
    val s = c.internal.enclosingOwner.name.decodedName.toString.stripSuffix(LOCAL_SUFFIX_STRING).stripSuffix("$lzy")

    val t = c.weakTypeOf[T]
    val r = c.weakTypeOf[R]

    c.Expr[B[T, R]](q"new B[$t,$r]($s)")
  }

  /**
    * This is an alias for [[Macros#run]], to be used in case [[Macros#run]] clashes
    * with another name imported into the local scope (e.g. in scalatest).
    * Examples: & { a(_) => ... }
    * & { a (_) => ...} onThreads threadPool
    */
  object & {
    // Users will create reactions using these functions.
    def apply(reactionBody: fmArg): Reaction = macro buildReactionImpl
  }

  private sealed trait PatternFlag {
    def notReplyValue: Boolean = this match {
      case ReplyVarF(_) => false
      case _ => true
    }

  }

  private case object WildcardF extends PatternFlag
  private final case class ReplyVarF(replyVar: Any) extends PatternFlag // "Any" is actually a replyVar name
  private case object SimpleVarF extends PatternFlag
  private case object WrongReplyVarF extends PatternFlag // the reply pseudo-molecule must be bound to a simple variable, but we found another pattern
  private final case class SimpleConstF(v: Any) extends PatternFlag // this is the [T] type of M[T] or B[T,R]
  private final case class OtherPatternF(matcher: Any) extends PatternFlag // "Any" is actually an expression tree for a partial function

  private sealed trait OutputPatternFlag
  private case object OtherOutputPatternF extends OutputPatternFlag
  private final case class ConstOutputPattern(v: Any) extends OutputPatternFlag

  private type fmArg = ReactionBody // UnapplyArg => Unit // ReactionBody

  /**
    * Users will define reactions using this function.
    * Examples: {{{ run { a(_) => ... } }}}
    * {{{ run { a (_) => ...} onThreads threadPool }}}
    *
    * The macro also obtains statically checkable information about input and output molecules in the reaction.
    *
    * @param reactionBody The body of the reaction. This must be a partial function with pattern-matching on molecules.
    * @return A reaction value, to be used later in [[JoinRun#join]].
    */
  def run(reactionBody: fmArg): Reaction = macro buildReactionImpl

  def buildReactionImpl(c: theContext)(reactionBody: c.Expr[fmArg]): c.universe.Tree = {
    import c.universe._

//    val reactionBodyReset = c.untypecheck(reactionBody.tree)

    /** Obtain the owner of the current macro call site.
      *
      * @return The owner symbol of the current macro call site.
      */
    def getCurrentSymbolOwner: c.Symbol = {
      val freshName = c.freshName(TypeName("Probe$"))
      val probe = c.typecheck(q""" {class $freshName; ()} """)
      probe match {
        case Block(List(t), r) => t.symbol.owner
      }
    }

    object ReactionCases extends Traverser {
      private var info: List[CaseDef] = List()

      override def traverse(tree: Tree): Unit =
        tree match {
          // this is matched by the partial function of type ReactionBody
          case DefDef(_, TermName("applyOrElse"), _, _, _, Match(_, list)) =>
            info = list

          // this is matched by a closure which is not a partial function
          case Function(List(ValDef(_, TermName(_), TypeTree(), EmptyTree)), Match(Ident(TermName(_)), list)) =>
           info = list

          case _ => super.traverse(tree)
        }

      def from(tree: Tree): List[(Tree, Tree, Tree, String)] = {
        info = List()
        this.traverse(tree)
        info.filter { // PartialFunction automatically adds a default case; we ignore that CaseDef.
            case CaseDef(Bind(TermName("defaultCase$"), Ident(termNames.WILDCARD)), EmptyTree, _) => false
            case _ => true
          }.map { case c@CaseDef(aPattern, aGuard, aBody) => (aPattern, aGuard, aBody, getSha1(c)) }
      }
    }

    def isSingletonReaction(pattern: Tree, guard: Tree, body: Tree): Boolean = {
      pattern match {
        case Ident(termNames.WILDCARD) => true
        case _ => false
      }
    }

    class MoleculeInfo(reactionBodyOwner: c.Symbol) extends Traverser {

      /** Examine an expression tree, looking for molecule expressions.
        *
        * @param reactionPart An expression tree (could be the "case" pattern, the "if" guard, or the reaction body).
        * @return A triple: List of input molecule patterns, list of output molecule patterns, and list of reply action patterns.
        */
      def from(reactionPart: Tree): (List[(c.Symbol, PatternFlag, Option[PatternFlag], String)], List[(c.Symbol, OutputPatternFlag)], List[(c.Symbol, OutputPatternFlag)]) = {
        inputMolecules = mutable.ArrayBuffer()
        outputMolecules = mutable.ArrayBuffer()
        replyActions = mutable.ArrayBuffer()
        traverse(reactionPart)
        (inputMolecules.toList, outputMolecules.toList, replyActions.toList)
      }

      private var inputMolecules: mutable.ArrayBuffer[(c.Symbol, PatternFlag, Option[PatternFlag], String)] = mutable.ArrayBuffer()
      private var outputMolecules: mutable.ArrayBuffer[(c.Symbol, OutputPatternFlag)] = mutable.ArrayBuffer()
      private var replyActions: mutable.ArrayBuffer[(c.Symbol, OutputPatternFlag)] = mutable.ArrayBuffer()

      private def getSimpleVar(binderTerm: Tree): c.universe.Ident = binderTerm match {
        case Bind(TermName(n), Ident(termNames.WILDCARD)) => Ident(TermName(n))
      }

      /** Detect whether the symbol {{{s}}} is defined inside the scope of the symbol {{{owner}}}.
        * Will return true for code like {{{ val owner = .... { val s = ... }  }}}
        *
        * @param s Symbol to be examined.
        * @param owner Owner symbol of the scope to be examined.
        * @return True if {{{s}}} is defined inside the scope of {{{owner}}}.
        */
      @tailrec
      private def isOwnedBy(s: c.Symbol, owner: c.Symbol): Boolean = s.owner match {
        case `owner` => owner != NoSymbol
        case `NoSymbol` => false
        case o@_ => isOwnedBy(o, owner)
      }

      private def getFlag(binderTerm: Tree): PatternFlag = binderTerm match {
        case Ident(termNames.WILDCARD) => WildcardF
        case Bind(TermName(_), Ident(termNames.WILDCARD)) => SimpleVarF
        case Literal(_) => SimpleConstF(binderTerm)
        case _ =>
          val partialFunctionTree: c.Tree = q"{ case $binderTerm => }"
          OtherPatternF(partialFunctionTree)
      }

      private def getOutputFlag(binderTerms: List[Tree]): OutputPatternFlag = binderTerms match {
        case List(t@Literal(_)) => ConstOutputPattern(t)
        case _ => OtherOutputPatternF
      }

      override def traverse(tree: Tree): Unit = {
        tree match {
          // avoid traversing nested reactions: check whether this subtree is a Reaction() value
          case q"code.winitzki.jc.JoinRun.Reaction.apply($_,$_,$_,$_)" => ()
          case q"JoinRun.Reaction.apply($_,$_,$_,$_)" => ()

          // matcher with a single argument: a(x)
          case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder)) if t.tpe <:< typeOf[Molecule] =>
            val flag2 = if (t.tpe <:< weakTypeOf[B[_,_]]) Some(WrongReplyVarF) else None
            inputMolecules.append((t.symbol, getFlag(binder), flag2, getSha1(binder)))

          // matcher with two arguments: a(x, y)
          case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), List(binder1, binder2)) if t.tpe <:< typeOf[Molecule] =>
            val flag2 = getFlag(binder2) match {
              case SimpleVarF => ReplyVarF(getSimpleVar(binder2))
              case f@_ => WrongReplyVarF // this is an error that we should report later
            }
            inputMolecules.append((t.symbol, getFlag(binder1), Some(flag2), getSha1(binder1)))

          // matcher with wrong number of arguments - neither 1 nor 2
          case UnApply(Apply(Select(t@Ident(TermName(_)), TermName("unapply")), List(Ident(TermName("<unapply-selector>")))), _)
            if t.tpe <:< typeOf[Molecule] =>
              inputMolecules.append((t.symbol, WrongReplyVarF, None, getSha1(t)))

          // possibly a molecule injection
          case Apply(Select(t@Ident(TermName(_)), TermName("apply")), binder) =>

            // In the output list, we do not include any molecule injectors defined in the inner scope.
            val includeThisSymbol = !isOwnedBy(t.symbol.owner, reactionBodyOwner)

            if (includeThisSymbol && t.tpe <:< typeOf[Molecule]) {
              outputMolecules.append((t.symbol, getOutputFlag(binder)))
            }

            if (t.tpe <:< weakTypeOf[ReplyValue[_,_]]) {
              replyActions.append((t.symbol, getOutputFlag(binder)))
            }

          case _ => super.traverse(tree)

        }
      }
    }

    // this boilerplate is necessary for being able to use PatternType values in macro quasiquotes
    implicit val liftablePatternFlag = Liftable[PatternFlag] {
      case WildcardF => q"_root_.code.winitzki.jc.JoinRun.Wildcard"
      case SimpleConstF(x) => q"_root_.code.winitzki.jc.JoinRun.SimpleConst(${x.asInstanceOf[c.Tree]})"
      case SimpleVarF => q"_root_.code.winitzki.jc.JoinRun.SimpleVar"
      case OtherPatternF(matcherTree) => q"_root_.code.winitzki.jc.JoinRun.OtherInputPattern(${matcherTree.asInstanceOf[c.Tree]})"
      case _ => q"_root_.code.winitzki.jc.JoinRun.UnknownInputPattern"
    }

    implicit val liftableOutputPatternFlag = Liftable[OutputPatternFlag] {
      case ConstOutputPattern(x) => q"_root_.code.winitzki.jc.JoinRun.ConstOutputValue(${x.asInstanceOf[c.Tree]})"
      case _ => q"_root_.code.winitzki.jc.JoinRun.OtherOutputPattern"
    }

    implicit val liftableGuardFlag = Liftable[GuardPresenceType] {
      case GuardPresent => q"_root_.code.winitzki.jc.JoinRun.GuardPresent"
      case GuardAbsent => q"_root_.code.winitzki.jc.JoinRun.GuardAbsent"
      case GuardPresenceUnknown => q"_root_.code.winitzki.jc.JoinRun.GuardPresenceUnknown"
    }

    def maybeError[T](what: String, patternWhat: String, molecules: Seq[T], connector: String = "not contain a pattern that", method: (c.Position, String) => Unit = c.error) = {
      if (molecules.nonEmpty)
        method(c.enclosingPosition, s"$what should $connector $patternWhat (${molecules.mkString(", ")})")
    }

    val caseDefs = ReactionCases.from(reactionBody.tree)
    // TODO: check other CaseDef's if any; check that all CaseDef's have the same input molecules.
    // - for now, we only look at the first case.
    val Some((pattern, guard, body, sha1)) = caseDefs.headOption

    val moleculeInfoMaker = new MoleculeInfo(getCurrentSymbolOwner)

    val (patternIn, patternOut, patternReply) = moleculeInfoMaker.from(pattern) // patternOut and patternReply should be empty
    maybeError("input molecules", "injects output molecules", patternOut)
    maybeError("input molecules", "injects reply molecules", patternReply)

    val (guardIn, guardOut, guardReply) = moleculeInfoMaker.from(guard) // guardIn should be empty
    maybeError("input guard", "matches on additional input molecules", guardIn.map(_._1))

    val (bodyIn, bodyOut, bodyReply) = moleculeInfoMaker.from(body) // bodyIn should be empty
    maybeError("reaction body", "matches on additional input molecules", bodyIn.map(_._1))

    val blockingMolecules = patternIn.filter(_._3.nonEmpty)
    // It is an error to have reply molecules that do not match on a simple variable.
    val wrongBlockingMolecules = blockingMolecules.filter(_._3.get.notReplyValue).map(_._1)
    maybeError("blocking input molecules", "matches on anything else than a simple variable", wrongBlockingMolecules)

    // If we are here, all input reply molecules are correctly matched. Now we check that each of them has one and only one reply.
    val repliedMolecules = (guardReply ++ bodyReply).map(_._1.asTerm.name.decodedName)
    val blockingReplies = blockingMolecules.flatMap(_._3.flatMap{
      case ReplyVarF(x) => Some(x.asInstanceOf[c.universe.Ident].name)
      case _ => None
    })

    val blockingMoleculesWithoutReply = blockingReplies diff repliedMolecules
    val blockingMoleculesWithMultipleReply = repliedMolecules diff blockingReplies
    maybeError("blocking input molecules", "but no reply found for", blockingMoleculesWithoutReply, "receive a reply")
    maybeError("blocking input molecules", "but multiple replies found for", blockingMoleculesWithMultipleReply, "receive only one reply")

    if (patternIn.isEmpty && !isSingletonReaction(pattern, guard, body))
      c.error(c.enclosingPosition, "Reaction should not have an empty list of input molecules")

    val inputMolecules = patternIn.map { case (s, p, _, sha1) => q"InputMoleculeInfo(${s.asTerm}, $p, $sha1)" }

    // Note: the output molecules could be sometimes not injected according to a runtime condition.
    // We do not try to examine the reaction body to determine which output molecules are always injected.
    // However, the order of output molecules corresponds to the order in which they might be injected.
    val allOutputInfo = guardOut ++ bodyOut
    val outputMolecules = allOutputInfo.map { case (m, p) => q"OutputMoleculeInfo(${m.asTerm}, $p)" }

    val isGuardAbsent = guard == EmptyTree
    val hasGuardFlag = if (isGuardAbsent) q"GuardAbsent" else q"GuardPresent"

    // Detect whether this reaction has a simple livelock:
    // All input molecules have trivial matchers and are a subset of output molecules.
    lazy val allInputMatchersAreTrivial = patternIn.forall{
      case (_, SimpleVarF, _, _) | (_, WildcardF, _, _) => true
      case _ => false
    }
    lazy val inputMoleculesAreSubsetOfOutputMolecules = (patternIn.map(_._1) diff allOutputInfo.map(_._1)).isEmpty

    if(isGuardAbsent && allInputMatchersAreTrivial && inputMoleculesAreSubsetOfOutputMolecules) {
      maybeError("Unconditional livelock: Input molecules", "output molecules, with all trivial matchers for", patternIn.map(_._1.asTerm.name.decodedName), "not be a subset of")
    }

    // Prepare the ReactionInfo structure.
    val result = q"Reaction(ReactionInfo($inputMolecules, Some(List(..$outputMolecules)), $hasGuardFlag, $sha1), $reactionBody, None, false)"
//    println(s"debug: ${show(result)}")
    result
  }

}
