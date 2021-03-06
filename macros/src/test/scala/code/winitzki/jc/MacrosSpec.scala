package code.winitzki.jc

import JoinRun._
import Macros._

import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt

class MacrosSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  val warmupTimeMs = 50

  val tp0 = new FixedPool(4)

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  override def afterAll(): Unit = {
    tp0.shutdownNow()
  }

  behavior of "macros for defining new molecule injectors"

  it should "compute invocation names for molecule injectors" in {
    val a = m[Int]

    a.toString shouldEqual "a"

    val s = b[Map[(Boolean, Unit), Seq[Int]], Option[List[(Int, Option[Map[Int, String]])]]] // complicated type

    s.toString shouldEqual "s/B"
  }

  behavior of "macros for inspecting a reaction body"

  it should "inspect reaction body with default clause that declares a singleton" in {
    val a = m[Int]

    val reaction = & { case _ => a(123) }

    reaction.info.inputs shouldEqual Nil
    reaction.info.hasGuard.knownFalse shouldEqual true
    reaction.info.outputs shouldEqual Some(List(OutputMoleculeInfo(a, ConstOutputValue(123))))
  }

  it should "inspect reaction body containing local molecule injectors" in {
    val a = m[Int]

    val reaction =
      & { case a(x) =>
        val q = new M[Int]("q")
        val s = new M[Unit]("s")
        val reaction1 = & { case q(_) + s(()) => }
        q(0)
      }
    reaction.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar, "8227489534FBEA1F404CAAEC9F4CCAEEB9EF2DC1"))
    reaction.info.outputs shouldEqual Some(List())
  }

  it should "inspect reaction body with embedded join" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    join(tp0, tp0)(
      & { case f(_, r) + bb(x) => r(x) },
      & { case a(x) =>
        val p = m[Int]
        join(tp0, tp0)(& { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    waitSome()
    f(timeout = 100 millis)() shouldEqual Some(2)
  }

  it should "inspect reaction body with embedded join and runSimple" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    join(tp0, tp0)(
      runSimple { case f(_, r) + bb(x) => r(x) },
      runSimple { case a(x) =>
        val p = m[Int]
        join(tp0, tp0)(& { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    waitSome()
    f(timeout = 100 millis)() shouldEqual Some(2)
  }

  val simpleVarXSha1 = "8227489534FBEA1F404CAAEC9F4CCAEEB9EF2DC1"
  val wildcardSha1 = "53A0ACFAD59379B3E050338BF9F23CFC172EE787"
  val constantZeroSha1 = "8227489534FBEA1F404CAAEC9F4CCAEEB9EF2DC1"
  val constantOneSha1 = "356A192B7913B04C54574D18C28D46E6395428AB"

  it should "inspect a runSimple reaction body" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    val result = runSimple { case a(x) + qq(_) => qq() }

    (result.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, UnknownInputPattern, _),
      InputMoleculeInfo(`qq`, UnknownInputPattern, _)
      ) => true
      case _ => false
    }) shouldEqual true
    result.info.outputs shouldEqual None
    result.info.hasGuard == GuardPresenceUnknown
    result.info.sha1 != "3C5E53F3B9EA1CC2AB58F463114B178EB569390F" shouldEqual true
  }

  it should "inspect a two-molecule reaction body with None" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[Option[Int]]

    val result = & { case a(x) + bb(None) => bb(None) }

    (result.info.inputs match {
      case List(
      InputMoleculeInfo(a, SimpleVar, simpleVarXSha1),
      InputMoleculeInfo(bb, OtherInputPattern(_), "4B93FCEF4617B49161D3D2F83E34012391D5A883")
      ) => true
      case _ => false
    }) shouldEqual true

    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(bb, OtherOutputPattern)))
    result.info.hasGuard == GuardAbsent
    result.info.sha1 shouldEqual "E3484A31FA12EA19A7D84422C452E1F8099F8117"
  }

  it should "inspect a two-molecule reaction body" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    val result = & { case a(x) + qq(_) => qq() }

    result.info.inputs shouldEqual List(
      InputMoleculeInfo(a, SimpleVar, simpleVarXSha1),
      InputMoleculeInfo(qq, Wildcard, wildcardSha1)
    )
    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(qq, ConstOutputValue(()))))
    result.info.hasGuard == GuardAbsent
    result.info.sha1 shouldEqual "3C5E53F3B9EA1CC2AB58F463114B178EB569390F"
  }

  it should "inspect a reaction body with another molecule and extra code" in {
    val a = m[Int]
    val qqq = m[String]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    object testWithApply {
      def apply(x: Int): Int = x + 1
    }

    val result = & {
      case a(_) + a(x) + a(1) =>
        a(x + 1)
        if (x > 0) a(testWithApply(123))
        println(x)
        qqq("")
    }

    result.info.inputs shouldEqual List(
      InputMoleculeInfo(a, Wildcard, wildcardSha1),
      InputMoleculeInfo(a, SimpleVar, simpleVarXSha1),
      InputMoleculeInfo(a, SimpleConst(1), constantOneSha1)
    )
    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(a,OtherOutputPattern), OutputMoleculeInfo(a,OtherOutputPattern), OutputMoleculeInfo(qqq,ConstOutputValue(""))))
    result.info.hasGuard == GuardAbsent
  }

  it should "inspect reaction body with embedded reaction" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    val result = & { case a(x) => & { case qq(_) => a(0) }; qq() }

    result.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar, simpleVarXSha1))
    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(qq,ConstOutputValue(()))))
    result.info.hasGuard == GuardAbsent
  }

  it should "inspect a very complicated reaction body" in {
    val a = m[Int]
    val c = m[Unit]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    // reaction contains all kinds of pattern-matching constructions, blocking molecule in a guard, and unit values in molecules
    val result = & {
      case a(p) + a(y) + a(1) + c(()) + c(_) + bb(_) + bb((1, z)) + bb((_, None)) + bb((t, Some(q))) + s(_, r) if y > 0 && s() > 0 => a(p + 1); qq(); r(p)
    }

    (result.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, SimpleVar, _),
      InputMoleculeInfo(`a`, SimpleVar, _),
      InputMoleculeInfo(`a`, SimpleConst(1), _),
      InputMoleculeInfo(`c`, SimpleConst(()), _),
      InputMoleculeInfo(`c`, Wildcard, _),
      InputMoleculeInfo(`bb`, Wildcard, _),
      InputMoleculeInfo(`bb`, OtherInputPattern(_), _),
      InputMoleculeInfo(`bb`, OtherInputPattern(_), _),
      InputMoleculeInfo(`bb`, OtherInputPattern(_), _),
      InputMoleculeInfo(`s`, Wildcard, _)
      ) => true
      case _ => false
    }) shouldEqual true

    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(s,ConstOutputValue(())), OutputMoleculeInfo(a,OtherOutputPattern), OutputMoleculeInfo(qq,ConstOutputValue(()))))
    result.info.hasGuard shouldEqual GuardPresent
  }

  it should "inspect reaction body with two cases" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = & {
      case a(x) => qq()
      case qq(_) + a(y) => qq()
    }
    // TODO: add a test for inspecting this reaction
  }

  it should "define a reaction with correct inputs with non-default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(runSimple { case b(_) + a(Some(x)) + c(_) => })

    a.logSoup shouldEqual "Join{a + b => ...}\nNo molecules" // this is the wrong result
    // when the problem is fixed, this test will have to be rewritten
  }

  it should "define a reaction with correct inputs with default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(& { case b(_) + a(None) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-simple default pattern-matching in the middle of reaction" in {
    val a = new M[Seq[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(& { case b(_) + a(List()) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "fail to define a simple reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(runSimple { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(& { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(& { case a(Some(x)) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "run reactions correctly with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")

    join(tp0, tp0)(& { case a(Some(x)) + b(_) => })

    a(Some(1))
    waitSome()
    a.logSoup shouldEqual "Join{a + b => ...}\nMolecules: a(Some(1))"
    b()
    waitSome()
    a.logSoup shouldEqual "Join{a + b => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at start of reaction" in {
    val a = new M[Int]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(& { case a(1) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant default option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0, tp0)(& { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "determine input patterns correctly" in {
    val a = new M[Option[Int]]("a")
    val b = new M[String]("b")
    val c = new M[(Int, Int)]("c")
    val d = new M[Unit]("d")

    val r = & { case a(Some(1)) + b("xyz") + d(()) + c((2, 3)) => a(Some(2)) }

    (r.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, OtherInputPattern(_),_),
      InputMoleculeInfo(`b`, SimpleConst("xyz"),_),
      InputMoleculeInfo(`d`, SimpleConst(()),_),
      InputMoleculeInfo(`c`, OtherInputPattern(_),_)
      ) => true
      case _ => false
    }) shouldEqual true
    r.info.outputs shouldEqual Some(List(OutputMoleculeInfo(a,OtherOutputPattern)))
    r.info.hasGuard shouldEqual GuardAbsent

    // Note: Scala 2.11 and Scala 2.12 have different syntax trees for Some(1)
    val shaScala211 = "3A03F935B238FBC427CCEC83D25D69820AB5CDBE"
    val shaScala212 = "C4A42A1C5082B4C5023CC3B0497BB7BCA6642C17"
    Set(shaScala211, shaScala212) should contain oneElementOf List(r.info.sha1)
  }

  it should "fail to compile reactions with detectable compile-time errors" in {
    val a = b[Unit, Unit]
    val c = b[Unit, Unit]
    val e = m[Unit]

    "val r = & { case e() => }" shouldNot compile // no pattern variable in a non-blocking molecule "e"
    "val r = & { case e(_,_) => }" shouldNot compile // two pattern variables in a non-blocking molecule "e"

    "val r = & { case a() => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = & { case a(_) => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = & { case a(_, _) => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = & { case a(_, _, _) => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = & { case a(_, r) => }" shouldNot compile // no reply is performed with r
    "val r = & { case a(_, r) + a(_) + c(_) => r()  }" shouldNot compile // invalid patterns for "a" and "c"
    "val r = & { case a(_, r) + a(_) + c(_) => r() + r() }" shouldNot compile // two replies are performed with r, and invalid patterns for "a" and "c"

  }

  it should "fail to compile reactions with no input molecules" in {
    val bb = m[Int]
    val bbb = m[Int]

    "val r = & { case _ => bb(0) }" should compile // declaration of a singleton
    "val r = & { case x => bb(x.asInstanceOf[Int]) }" shouldNot compile // no input molecules
    "val r = & { case x => x }" shouldNot compile // no input molecules
  }

  it should "fail to compile reactions with unconditional livelock" in {
    val bb = m[Int]
    val bbb = m[Int]
    val a = m[(Int, Int)]

    "val r = & { case a((x,y)) => a((1,1)) }" should compile // cannot detect unconditional livelock here
    "val r = & { case a((_,x)) => a((x,x)) }" should compile // cannot detect unconditional livelock here
    "val r = & { case a((1,_)) => a((1,1)) }" should compile // cannot detect unconditional livelock here

    "val r = & { case bb(x) => bb(1) }" shouldNot compile // unconditional livelock
    "val r = & { case bb(x) if x > 0 => bb(1) }" should compile // no unconditional livelock due to guard

    "val r = & { case a(_) => a((1,1)) }" shouldNot compile // unconditional livelock

    "val r = & { case bbb(1) => bbb(2) }" should compile // no unconditional livelock

    "val r = & { case bbb(_) => bbb(0) }" shouldNot compile // unconditional livelock
    "val r = & { case bbb(x) => bbb(x) + bb(x) }" shouldNot compile
    "val r = & { case bbb(x) + bb(y) => bbb(x) + bb(x) + bb(y) }" shouldNot compile
  }

  it should "detect output molecules with constant values" in {
    val bb = m[Int]
    val bbb = m[Int]
    val cc = m[Option[Int]]

    val r1 = & { case bbb(x) => bb(x) }
    val r2 = & { case bbb(_) + bb(_) => bbb(0) }
    val r3 = & { case bbb(x) + bb(_) + bb(_) => bbb(1) + bb(x) + bbb(2) + cc(None) + cc(Some(1)) }

    r1.info.outputs shouldEqual Some(List(OutputMoleculeInfo(bb,OtherOutputPattern)))
    r2.info.outputs shouldEqual Some(List(OutputMoleculeInfo(bbb,ConstOutputValue(0))))
    r3.info.outputs shouldEqual Some(List(
      OutputMoleculeInfo(bbb,ConstOutputValue(1)),
      OutputMoleculeInfo(bb,OtherOutputPattern),
      OutputMoleculeInfo(bbb,ConstOutputValue(2)),
      OutputMoleculeInfo(cc,OtherOutputPattern),
      OutputMoleculeInfo(cc,OtherOutputPattern)
    ))
  }

  it should "create partial functions for matching from reaction body" in {
    val aa = m[Option[Int]]
    val bb = m[(Int, Option[Int])]

    val result = & { case aa(Some(x)) + bb((0, None)) => aa(Some(x + 1)) }

    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(aa,OtherOutputPattern)))

    val pat_aa = result.info.inputs(0)
    pat_aa.molecule shouldEqual aa
    val pat_bb = result.info.inputs(1)
    pat_bb.molecule shouldEqual bb

    (pat_aa.flag match {
      case OtherInputPattern(matcher) =>
        matcher.isDefinedAt(Some(1)) shouldEqual true
        matcher.isDefinedAt(None) shouldEqual false
        true
      case _ => false
    }) shouldEqual true

    // Scala 2.11 vs. Scala 2.12
    (Set("9247828A8E7754B2D961E955541CF1D4D77E2D1E","A67750BF5B6338391B0034D3A99694889CBB26A3") contains pat_aa.sha1) shouldEqual true

    (pat_bb.flag match {
      case OtherInputPattern(matcher) =>
        matcher.isDefinedAt((0, None)) shouldEqual true
        matcher.isDefinedAt((1, None)) shouldEqual false
        matcher.isDefinedAt((0, Some(1))) shouldEqual false
        matcher.isDefinedAt((1, Some(1))) shouldEqual false
        true
      case _ => false
    }) shouldEqual true

    (Set("2FB215E623E8AF28E9EA279CBEA827A1065CA226","A67750BF5B6338391B0034D3A99694889CBB26A3") contains pat_bb.sha1) shouldEqual true

  }

  behavior of "output value computation"

  it should "not fail to compute outputs for an inline reaction" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      join(
        & { case a(1) => a(1) }
      )
      a.consumingReactions.get.map(_.info.outputs) shouldEqual Set(Some(List(OutputMoleculeInfo(a, ConstOutputValue(1)))))
    }
    thrown.getMessage shouldEqual "In Join{a => ...}: Unavoidable livelock: reaction a => ..."
  }

  it should "compute inputs and outputs correctly for an inline nested reaction" in {
    val a = m[Int]
    join(
      & {
        case a(1) =>
          val c = m[Int]
          join(& { case c(_) => })
          c(2)
          a(2)
      }
    )
    a.consumingReactions.get.map(_.info.outputs) shouldEqual Set(Some(List(OutputMoleculeInfo(a, ConstOutputValue(2)))))
    a.consumingReactions.get.map(_.info.inputs) shouldEqual Set(List(InputMoleculeInfo(a, SimpleConst(1), constantOneSha1)))
    a.injectingReactions.map(_.info.outputs) shouldEqual Set(Some(List(OutputMoleculeInfo(a, ConstOutputValue(2)))))
    a.injectingReactions.map(_.info.inputs) shouldEqual Set(List(InputMoleculeInfo(a, SimpleConst(1), constantOneSha1)))
  }

  it should "not fail to compute outputs correctly for an inline nested reaction" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      join(
        & {
          case a(1) =>
            val c = m[Int]
            join(& { case c(_) => })
            c(2)
            a(1)
        }
      )
    }
    thrown.getMessage shouldEqual "In Join{a => ...}: Unavoidable livelock: reaction a => ..."
  }

  it should "compute outputs in the correct order for a reaction with no livelock" in {
    val a = m[Int]
    val b = m[Int]
    join(
      & { case a(2) => b(2) + a(1) + b(1) }
    )
    a.consumingReactions.get.map(_.info.outputs) shouldEqual Set(Some(List(
      OutputMoleculeInfo(b, ConstOutputValue(2)),
      OutputMoleculeInfo(a, ConstOutputValue(1)),
      OutputMoleculeInfo(b, ConstOutputValue(1))
    )))
  }

  behavior of "auxiliary functions"

  it should "find expression trees for constant values" in {
    rawTree(1) shouldEqual "Literal(Constant(1))"
    rawTree(None) shouldEqual "Select(Ident(scala), scala.None)"

    (Set(
        "Apply(TypeApply(Select(Select(Ident(scala), scala.Some), TermName(\"apply\")), List(TypeTree())), List(Literal(Constant(1))))"
    ) contains rawTree(Some(1))) shouldEqual true
  }

  it should "find expression trees for matchers" in {

    rawTree(Some(1) match {case Some(1) => } ) shouldEqual "Match(Apply(TypeApply(Select(Select(Ident(scala), scala.Some), TermName(\"apply\")), List(TypeTree())), List(Literal(Constant(1)))), List(CaseDef(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Literal(Constant(1)))), EmptyTree, Literal(Constant(())))))"
  }

  it should "find enclosing symbol names with correct scopes" in {
    val x = getName

    x shouldEqual "x"

    val y = {
      val z = getName
      (z, getName)
    }
    y shouldEqual ("z", "y")
  }

}

