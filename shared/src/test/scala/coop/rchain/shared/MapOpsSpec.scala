package coop.rchain.shared

import org.scalatest._
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class MapOpsSpec extends FlatSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  import MapOps._

  "zip" should "retain all keys" in forAll { (m1: Map[String, Int], m2: Map[String, Int]) =>
    zip(m1, m2, 0, 0).keys should contain theSameElementsAs (m1.keys ++ m2.keys)
  }

  it should "retain all values" in forAll { (m1: Map[String, Int], m2: Map[String, Int]) =>
    val result = zip(m1, m2, 0, 0)

    for {
      key <- result.keys
    } yield {
      if (m1.contains(key) && m2.contains(key))
        result(key) shouldBe ((m1(key), m2(key)))
      else if (m1.contains(key) && !m2.contains(key))
        result(key) shouldBe ((m1(key), 0))
      else if (!m1.contains(key) && m2.contains(key))
        result(key) shouldBe ((0, m2(key)))
      else
        result(key) shouldBe ((0, 0))
    }

  }

}
