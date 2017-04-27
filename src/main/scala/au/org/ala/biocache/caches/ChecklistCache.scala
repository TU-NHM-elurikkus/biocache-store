package au.org.ala.biocache.caches

import au.org.ala.biocache.Config
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.io.Source

object ChecklistCache {
  val logger = LoggerFactory.getLogger("ChecklistCache")

  private val guids:mutable.Set[Int] = new mutable.HashSet[Int]()
  private var initialized = false
  private val initLock = new Object()

  def contains(guid: Int) : Boolean = {
    if(!initialized) {
      load(Config.checklistPath)
    }

    guids.contains(guid)
  }

  def load(path: String) = {
    initLock.synchronized {
      // Second initialized check in case a second thread enters load during initialization
      if (!path.isEmpty() && !initialized) {
        for (line <- Source.fromFile(path).getLines()) {
          guids.add(Integer.parseInt(line))
        }
      }

      initialized = true
    }
  }
}
