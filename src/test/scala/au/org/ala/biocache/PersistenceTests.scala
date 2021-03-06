package au.org.ala.biocache

import org.scalatest.FunSuite
import org.junit.Ignore
import au.org.ala.biocache.model.QualityAssertion

@Ignore
class PersistenceTests extends FunSuite {

    test("simple put without id"){
        val uuid = Config.persistenceManager.put(null, "test", "dave-property", "dave-value", false)
        val retrievedValue = Config.persistenceManager.get(uuid, "test", "dave-property")
        expectResult("dave-value"){retrievedValue.getOrElse("")}
    }

    test("Simple put list"){
        val list = List(QualityAssertion(1),QualityAssertion(2))
        val uuid = ((uuid: _root_.scala.Predef.String, entityName: _root_.scala.Predef.String, propertyName: _root_.scala.Predef.String, objectList: scala.Seq[Any], theClass: _root_.java.lang.Class[_], overwrite: Boolean, deleteIfNullValue: Boolean) => Config.persistenceManager.putList(uuid, entityName, propertyName, objectList, theClass, overwrite, deleteIfNullValue))(null, "test", "mylist", list, classOf[QualityAssertion], true, false)

        //retrieve the list
        println("UUID: " + uuid)
        val retrievedList = Config.persistenceManager.getList[QualityAssertion](uuid, "test", "mylist", classOf[QualityAssertion])
        expectResult(2){retrievedList.size}
        expectResult(1){retrievedList.head.code}
    }
}