package code.winitzki.jc

import JoinRun._
import Library._
import org.scalactic.source.Position
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.Waiters.{PatienceConfig, Waiter}
import org.scalatest.time.{Millis, Span}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LibrarySpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  val warmupTimeMs = 50

  val patienceConfig = PatienceConfig(timeout = Span(500, Millis))

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "future + molecule"

  it should "inject a molecule from a future computed out of a given future" in {

    val c = new M[Unit]("c")
    val f = new B[Unit, Unit]("f")

    val tp = new FixedPool(2)
    join(tp)(
      runSimple { case c(_) + f(_, r) => r() }
    )

    Future { Thread.sleep(50) } & c    // insert a molecule from the end of the future

    f() shouldEqual ()
    tp.shutdownNow()
  }

  it should "inject a molecule from a future with a lazy injection" in {
    val waiter = new Waiter

    val c = new M[String]("c")
    val tp = new FixedPool(2)

    join(tp)(
      runSimple { case c(x) => waiter {x shouldEqual "send it off"}; waiter.dismiss() }
    )

    Future { Thread.sleep(50) } + c("send it off")    // insert a molecule from the end of the future

    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

  it should "not inject a molecule from a future prematurely" in {
    val waiter = new Waiter

    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val e = new M[Unit]("e")
    val f = new B[Unit, String]("f")
    val f2 = new B[Unit, String]("f2")

    val tp = new FixedPool(4)
    join(tp)(
      runSimple { case e(_) + c(_) => d() },
      runSimple { case c(_) + f(_, r) => r("from c"); c() },
      runSimple { case d(_) + f2(_, r) => r("from d") }
    )

    c()

    val givenFuture = Future {
      Thread.sleep(20)
    } // waiter has 150 ms timeout

    (givenFuture + e()).map { _ => waiter.dismiss() }
    // The test would fail if e() were injected right away at this point.

    f() shouldEqual "from c"
    waiter.await()
    f2() shouldEqual "from d"

    tp.shutdownNow()
  }

  behavior of "#moleculeFuture"

  it should "create a future that succeeds when molecule is injected" in {
    val waiter = new Waiter
    val tp = new FixedPool(4)

    val b = new M[Unit]("b")

    // "fut" will succeed when "c" is injected
    val (c, fut) = moleculeFuture[String](tp)

    join(tp)(
      runSimple { case b(_) => c("send it off") }
    )

    val givenFuture = for {
      _ <- Future {
        Thread.sleep(20)
      } // waiter has 150 ms timeout
      s <- fut
    } yield {
      waiter {
        s shouldEqual "send it off"
      }
      waiter.dismiss()
    }

    b()
    waiter.await()(patienceConfig, implicitly[Position])

    tp.shutdownNow()
  }

}