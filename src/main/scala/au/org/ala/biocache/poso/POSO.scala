package au.org.ala.biocache.poso

import scala.collection.mutable.HashMap
import scala.collection.immutable.Map
import scala.util.parsing.json.JSON
import org.codehaus.jackson.annotate.JsonIgnore
import org.slf4j.LoggerFactory
import au.org.ala.biocache.util.{BiocacheConversions, StringHelper, Json}
import au.org.ala.biocache.parser.DateParser

/**
 * A trait for POSOs that allows for setting of values using
 * reflection.
 */
trait POSO {

  val logger = LoggerFactory.getLogger("POSO")
  import scala.collection.JavaConversions._
  import BiocacheConversions._

  protected val lookup = ReflectionCache.getPosoLookup(this)

  val propertyNames = lookup.values.map(v => v.name)

  /**
   * Case insensitive property check.
   *
   * @param name
   * @return
   */
  def hasProperty(name: String) = !lookupProperty(name).isEmpty

  /**
   * Case insensitive property lookup.
   *
   * @param name
   * @return
   */
  def lookupProperty(name:String) : Option[ModelProperty] = lookup.get(name.toLowerCase)

  /**
   * Clear all properties for this POSO.
   */
  def clearAllProperties = propertyNames.foreach { propertyName =>
    lookupProperty(propertyName) match {
      case Some(modelProperty) => {
        val value = modelProperty.getter.invoke(this)
        if(value !=null && value.toString !=""){
          setProperty(propertyName, "")
        }
      }
      case None => //do nothing
    }
  }

  def setProperty(name: String, value: String) = lookupProperty(name) match {
    case Some(property) => {
      property.typeName match {
        case "java.lang.String" => property.setter.invoke(this, value)
        case "[Ljava.lang.String;" => {
          try {
            val array = Json.toArray(value, classOf[String].asInstanceOf[java.lang.Class[AnyRef]])
            property.setter.invoke(this, array)
          } catch {
            case e: Exception => {
              logger.error("Problem de-serialising value: " + name  + " : " + value + " - " + e.getMessage, e)
            }
          }
        }
        case "int" => property.setter.invoke(this, Integer.parseInt(value).asInstanceOf[AnyRef])
        case "double" => property.setter.invoke(this, java.lang.Double.parseDouble(value).asInstanceOf[AnyRef])
        case "boolean" => property.setter.invoke(this, java.lang.Boolean.parseBoolean(value).asInstanceOf[AnyRef])
        case "java.lang.Boolean" => property.setter.invoke(this, java.lang.Boolean.parseBoolean(value).asInstanceOf[AnyRef])
        case "scala.collection.immutable.Map" => {
          try {
            val fromJson = JSON.parseFull(value)
            if (fromJson.isDefined && !fromJson.isEmpty)
              property.setter.invoke(this, fromJson.get.asInstanceOf[Map[String, String]])
          }
          catch {
            case e: Exception => logger.warn("Unable to set POSO map property. Property: " + name  + " : " + value + " Error:" + e.getMessage)
          }
        }
        case "java.util.Map" => property.setter.invoke(this, Json.toJavaStringMap(value))
        case "java.util.Date" => {
          def date = DateParser.parseStringToDate(value)
          if(date.isDefined)
            property.setter.invoke(this, date.get)
        }
        case _ => logger.warn("Unhandled data type: " + property.typeName + " Property: " + name  + " : " + value )
      }
    }
    case None => {} //println("Property not mapped: " +name +", on " + this.getClass.getName)
  }

  def getProperty(name: String): Option[String] = lookupProperty(name) match {

    case Some(property) => {
      val value = {
        property.typeName match {
          case "java.lang.String" => property.getter.invoke(this)
          case "[Ljava.lang.String;" => {
            try {
              val array = property.getter.invoke(this)
              if (array != null)
                Json.toJSON(array.asInstanceOf[Array[AnyRef]])
              else
                null
            } catch {
              case e: Exception => logger.error(e.getMessage, e); null
            }
          }
          case "int" => property.getter.invoke(this)
          case "double" => property.getter.invoke(this)
          case "boolean" => property.getter.invoke(this)
          case "scala.collection.immutable.Map" => {
            try {
              val map = property.getter.invoke(this)
              if(map != null)
                Json.toJSONMap(map.asInstanceOf[Map[String,Any]])
              else None
            }
            catch {
              case e: Exception => logger.error(e.getMessage, e); null
            }
          }
          case "java.util.Map" => {
            try {
              val javaMap = property.setter.invoke(this)
              if (javaMap != null) {
                Json.toJSONMap(javaMap.asInstanceOf[Map[String, Any]])
              } else {
                null
              }
            } catch {
              case e: Exception => logger.error(e.getMessage, e); null
            }
          }
          case _ => null
        }
      }
      if (value != null) {
        Some(value.toString)
      } else {
        None
      }
    }
    case None => None //println("Property not mapped " +name +", on " + this.getClass.getName); None;
  }

  @JsonIgnore
  def getPropertyNames: List[String] = lookup.values.map(v => v.name).toList

  def toMap: Map[String, String] = {
    toMap(true)
  }

  def toMap(includeEmpty:Boolean): Map[String, String] = {
    val map = new HashMap[String, String]
    lookup.values.foreach(property => {

      //println("************* POSO.toMap field name: " + property.name)
      val unparsed = property.getter.invoke(this)

      property.typeName match {
        case "java.lang.String" => {
          var value = unparsed.asInstanceOf[String]
          if (value == null) {
            value = ""
          }
          if (includeEmpty || value != "") {
            map.put(property.name, value)
          }
        }
        case "[Ljava.lang.String;" => {
          try {
            val value = unparsed.asInstanceOf[Array[AnyRef]]
            if (value != null && value.length > 0) {
              val array = Json.toJSON(value)
              map.put(property.name, array)
            }
          } catch {
            case e: Exception => logger.error(e.getMessage, e)
          }
        }
        case "int" => {
          val value = unparsed.asInstanceOf[Int]
          map.put(property.name, value.toString)
        }
        case "double" => {
          val value = unparsed.asInstanceOf[Double]
          map.put(property.name, value.toString)
        }
        case "boolean" => {
          val value = unparsed.asInstanceOf[Boolean]
          map.put(property.name, value.toString)
        }
        case "scala.collection.immutable.Map" => {
          val value = unparsed.asInstanceOf[Map[String, String]]
          if (value != null && !value.isEmpty) {
            val stringValue = Json.toJSON(value)
            map.put(property.name, stringValue)
          }
        }
        case "java.util.Map" => {
          //val value = unparsed.asInstanceOf[Map[String,String]]
          val value = unparsed.asInstanceOf[java.util.Map[String, String]]
          if (!value.isEmpty) {
            val stringValue = Json.toJSON(value)
            map.put(property.name, stringValue)
          }
        }
        case "java.util.Date" => {
          map.put(property.name, unparsed.asInstanceOf[java.util.Date])
        }
        case _ => {
          if (unparsed.isInstanceOf[POSO]) {
            map ++ unparsed.asInstanceOf[POSO].toMap
          } else {
            throw new UnsupportedOperationException("Unsupported field type " + property.typeName)
          }
        }
      }

    })
    map.toMap
  }
}
