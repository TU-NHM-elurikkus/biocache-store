package au.org.ala.biocache

import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.scalatest.Assertions._
import au.org.ala.biocache.parser.CollectorNameParser


@RunWith(classOf[JUnitRunner])
class CollectorParserTest extends FunSuite {

  test("Support people with 'and' in name") {
    expectResult(List("Meelis Liivarand")){CollectorNameParser.parseForList("Meelis Liivarand").get}
  }
}