package code.winitzki.test

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._
import code.winitzki.jc.WarningsAndErrors
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

class StaticAnalysisSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(1000, Millis)

  val warmupTimeMs = 50

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "analysis of reaction shadowing"

  it should "detect shadowing of simplest reactions" in {
    val thrown = intercept[Exception] {
      val a = m[Unit]
      val b = m[Unit]
      join(
        & { case a(_) => b() },
        & { case a(_) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a => ...; a => ...}: Unavoidable indeterminism: reaction a => ... is shadowed by a => ..., reaction a => ... is shadowed by a => ..."
  }

  it should "detect shadowing of reactions with wildcards" in {
    val thrown = intercept[Exception] {
      val a = m[Unit]
      val b = m[Unit]
      join(
        & { case a(_) => },
        & { case a(_) + b(_) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ..."
  }

  it should "detect shadowing of reactions with infallible matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      join(
        & { case a(x) => },
        & { case a(1) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ..."
  }

  it should "detect no shadowing of reactions with nontrivial matchers" in {
    val a = m[Int]
    val b = m[Unit]
    val result = join(
      & { case a(1) => },
      & { case a(_) + b(_) => }
    )
    result.hasErrorsOrWarnings shouldEqual false
  }

  it should "detect no shadowing of reactions with guards" in {
    val a = m[Int]
    val b = m[Unit]
    val result = join(
      & { case a(x) if x > 0 => },
      & { case a(_) + b(_) => }
    )
    result.hasErrorsOrWarnings shouldEqual false
  }

  it should "detect shadowing of reactions with identical constant matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      join(
        & { case a(1) => },
        & { case a(1) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ..."
  }

  it should "detect shadowing of reactions with identical non-constant matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      join(
        & { case a(Some(1)) => },
        & { case a(Some(1)) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ..."
  }

  it should "fail to detect shadowing of reactions with non-identical non-constant matchers" in {
    val a = m[Option[Int]]
    val b = m[Int]
    val result = join(
      & { case a(Some(_)) => },
      & { case a(Some(1)) + b(2) => }
    )
    result.hasErrorsOrWarnings shouldEqual false
  }

  it should "detect shadowing of reactions with non-identical matchers that match a constant and a wildcard" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      join(
        & { case b(_) + a(Some(1)) => },
        & { case a(Some(1)) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a + b => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a + b => ..."
  }

  object IsEven {
    def unapply(x: Int): Option[Int] = if (x % 2 == 0) Some(x/2) else None
  }

  it should "detect shadowing of reactions with non-identical matchers that are nontrivially weaker" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      join(
        & { case a(IsEven(x)) => },
        & { case a(2) + b(3) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ..."
  }

  it should "detect shadowing of reactions with non-identical matchers that are nontrivially not weaker" in {

    val a = m[Int]
    val b = m[Int]
    val result = join(
      & { case a(IsEven(x)) => },
      & { case a(1) + b(3) => }
    )
    result.hasErrorsOrWarnings shouldEqual false
  }

  it should "detect shadowing of reactions with all supported matcher combinations" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      join(
        & { case a(_) + b(1) + a(Some(2)) + a(x) + b(1) + b(_) => },
        & { case a(Some(1)) + b(2) + a(Some(2)) + a(Some(3)) + b(1) + b(_) + b(1) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + a + a + b + b + b + b => ...; a + a + a + b + b + b => ...}: Unavoidable indeterminism: reaction a + a + a + b + b + b + b => ... is shadowed by a + a + a + b + b + b => ..."
  }

  it should "detect shadowing of reactions with several wildcards" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      join(
        & { case a(_) + b(1) + a(Some(2)) + a(x) + a(_) => },
        & { case a(Some(1)) + b(2) + a(Some(2)) + a(Some(3)) + b(1) + b(_) + b(1) + a(x) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + a + a + a + b + b + b + b => ...; a + a + a + a + b => ...}: Unavoidable indeterminism: reaction a + a + a + a + b + b + b + b => ... is shadowed by a + a + a + a + b => ..."
  }

  behavior of "analysis of livelock"

  it should "detect livelock in a single reaction due to constant output values" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]

      join(& { case a(1) + b(_) => b(1) + b(2) + a(1) })

    }
    thrown.getMessage shouldEqual "In Join{a + b => ...}: Unavoidable livelock: reaction a + b => ..."
  }

  it should "not detect livelock in a single reaction due to different constant output values" in {
    val a = m[Int]
    val b = m[Int]
    val result = join(
      & { case a(1) + b(3) => b(1) + b(2) + a(1) }
    )
    result.hasErrorsOrWarnings shouldEqual false
  }

  it should "detect possible livelock in a single reaction due to nontrivial matchers" in {
    val a = m[Int]
    val result = join(
      & { case a(IsEven(x)) => a(x) }
    )
    result shouldEqual WarningsAndErrors(List("Possible livelock: reaction a(<A854...>) => a(?)"),List(),"Join{a => ...}")
  }

  it should "detect possible livelock in a single reaction due to guard" in {
    val a = m[Int]
    val result = join(
      & { case a(x) if x > 0 => a(x) }
    )
    result shouldEqual WarningsAndErrors(List("Possible livelock: reaction a(.) if(...) => a(?)"),List(),"Join{a => ...}")
  }

  it should "detect livelock in a single reaction due to constant output values with nontrivial matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      val c = m[Int]

      join(
        & { case b(IsEven(x)) + b(_) + a(_) + c(1) => c(1) + b(1) + b(2) + a(Some(1)) + c(2) }
      )

    }
    thrown.getMessage shouldEqual "In Join{a + b + b + c => ...}: Unavoidable livelock: reaction a + b + b + c => ..."
  }

  it should "detect livelock in a simple reaction due to constant output values" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      join(
        & { case a(1) => a(1) }
      )
    }
    thrown.getMessage shouldEqual "In Join{a => ...}: Unavoidable livelock: reaction a => ..."
  }

  it should "detect livelock in a single reaction due to constant output values without value assigning" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      join(
        & { case a(1) + b(_) => b(1) + b(2) + a(1) }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...}: Unavoidable livelock: reaction a + b => ..."
  }

  it should "give a livelock warning in a single reaction due to constant output values" in {
    val p = m[Int]
    val q = m[Int]
    val warnings = join(
      & { case p(x) + q(1) => q(x) + q(2) + p(1) } // Will have livelock when x==1, but not otherwise.
    )

    warnings shouldEqual WarningsAndErrors(List("Possible livelock: reaction p(.) + q(1) => q(?) + q(2) + p(1)"),List(),"Join{p + q => ...}")
  }

  it should "detect shadowing together with livelock" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]

      join(
        & { case a(1) + b(_) => b(1) + b(2) + a(1) },
        & { case a(IsEven(x)) => a(2) },
        & { case a(2) + b(3) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ...; Unavoidable livelock: reactions a + b => ..., a => ..."
  }

  behavior of "deadlock detection"

  it should "not warn about likely deadlock for a reaction that injects molecules for itself in the right order" in {
    val a = m[Int]
    val c = m[Int]
    val f = b[Unit, Int]

    val warnings = join(
      & { case f(_, r) + a(_) + c(_) => r(0); a(1); f() }
    )
    warnings shouldEqual WarningsAndErrors(Nil, Nil, "Join{a + c + f/B => ...}")
  }

  it should "warn about likely deadlock for a reaction that injects molecules for itself" in {
    val a = m[Int]
    val c = m[Int]
    val f = b[Unit, Int]

    val warnings = join(
      & { case f(_, r) + a(_) + c(_) => f(); r(0); a(1) }
    )
    warnings shouldEqual WarningsAndErrors(List("Possible deadlock: molecule f/B may deadlock due to outputs of a(_) + c(_) + f/B(_) => f/B() + a(1)", "Possible deadlock: molecule (f/B) may deadlock due to (a) among the outputs of a(_) + c(_) + f/B(_) => f/B() + a(1)"),List(),"Join{a + c + f/B => ...}")
  }

  it should "warn about likely deadlock for a reaction that injects molecules for another reaction" in {
    val a = m[Int]
    val c = m[Int]
    val f = b[Unit, Int]

    val warnings1 = join(
      & { case f(_, r) + a(_) => r(0); a(1) }
    )

    val warnings2 = join(
      & { case c(_) => f(); a(1) }
    )
    warnings1 shouldEqual WarningsAndErrors(Nil, Nil, "Join{a + f/B => ...}")
    warnings2 shouldEqual WarningsAndErrors(List("Possible deadlock: molecule f/B may deadlock due to outputs of a(_) + f/B(_) => a(1)"),List(),"Join{c => ...}")
  }

}
