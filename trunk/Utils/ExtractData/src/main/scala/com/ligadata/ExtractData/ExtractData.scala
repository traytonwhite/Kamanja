package com.ligadata.ExtractData

import scala.reflect.runtime.universe
import scala.collection.mutable.ArrayBuffer
import java.util.Properties
import org.apache.log4j.Logger
import java.io.{ OutputStream, FileOutputStream, File, BufferedWriter, Writer, PrintWriter }
import java.util.zip.GZIPOutputStream
import java.nio.file.{ Paths, Files }
import scala.reflect.runtime.{ universe => ru }
import java.net.{ URL, URLClassLoader }
import scala.collection.mutable.TreeSet
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.ligadata.Serialize._
import com.ligadata.FatafatData.FatafatData
import com.ligadata.MetadataAPI.MetadataAPIImpl
import com.ligadata.fatafat.metadata.MdMgr._
import com.ligadata.fatafat.metadata._
import com.ligadata.keyvaluestore._
import com.ligadata.keyvaluestore.mapdb._
import com.ligadata.FatafatBase._
import com.ligadata.fatafat.metadataload.MetadataLoad
import com.ligadata.Utils.Utils

case class DataStoreInfo(StoreType: String, SchemaName: String, Location: String)
case class FatafatDataKey(T: String, K: List[String], D: List[Int], V: Int)

// ClassLoader
class ExtractDataClassLoader(urls: Array[URL], parent: ClassLoader) extends URLClassLoader(urls, parent) {
  override def addURL(url: URL) {
    super.addURL(url)
  }
}

object ExtractData extends MdBaseResolveInfo {
  private val LOG = Logger.getLogger(getClass);
  private val clsLoader = new ExtractDataClassLoader(ClassLoader.getSystemClassLoader().asInstanceOf[URLClassLoader].getURLs(), getClass().getClassLoader())
  private val loadedJars: TreeSet[String] = new TreeSet[String];
  private val _serInfoBufBytes = 32
  private var _currentMessageObj: BaseMsgObj = null
  private var _currentContainerObj: BaseContainerObj = null
  private var _currentTypName: String = ""
  private var jarPaths: Set[String] = null
  private var _allDataDataStore: DataStore = null

  private type OptionMap = Map[Symbol, Any]

  private def PrintUsage(): Unit = {
    LOG.warn("Available options:")
    LOG.warn("    --config <configfilename>")
  }

  override def getMessgeOrContainerInstance(MsgContainerType: String): MessageContainerBase = {
    if (MsgContainerType.compareToIgnoreCase(_currentTypName) == 0) {
      if (_currentMessageObj != null)
        return _currentMessageObj.CreateNewMessage
      if (_currentContainerObj != null)
        return _currentContainerObj.CreateNewContainer
    }
    return null
  }

  private def GetValidJarFile(jarName: String): String = {
    if (jarPaths == null) return jarName // Returning base jarName if no jarpaths found
    jarPaths.foreach(jPath => {
      val fl = new File(jPath + "/" + jarName)
      if (fl.exists) {
        return fl.getPath
      }
    })
    return jarName // Returning base jarName if not found in jar paths
  }

  private def LoadJars(elem: BaseElem): Boolean = {
    var retVal: Boolean = true
    var allJars: Array[String] = null

    val jarname = if (elem.JarName == null) "" else elem.JarName.trim

    if (elem.DependencyJarNames != null && elem.DependencyJarNames.size > 0 && jarname.size > 0) {
      allJars = elem.DependencyJarNames :+ jarname
    } else if (elem.DependencyJarNames != null && elem.DependencyJarNames.size > 0) {
      allJars = elem.DependencyJarNames
    } else if (jarname.size > 0) {
      allJars = Array(jarname)
    } else {
      return retVal
    }

    val jars = allJars.map(j => GetValidJarFile(j))

    // Loading all jars
    for (j <- jars) {
      val jarNm = j.trim
      LOG.debug("%s:Processing Jar: %s".format(GetCurDtTmStr, jarNm))
      val fl = new File(jarNm)
      if (fl.exists) {
        try {
          if (loadedJars(fl.getPath())) {
            LOG.debug("%s:Jar %s already loaded to class path.".format(GetCurDtTmStr, jarNm))
          } else {
            clsLoader.addURL(fl.toURI().toURL())
            LOG.debug("%s:Jar %s added to class path.".format(GetCurDtTmStr, jarNm))
            loadedJars += fl.getPath()
          }
        } catch {
          case e: Exception => {
            val errMsg = "Jar " + jarNm + " failed added to class path. Reason:%s Message:%s".format(e.getCause, e.getMessage)
            throw new Exception(errMsg)
          }
        }
      } else {
        val errMsg = "Jar " + jarNm + " not found"
        throw new Exception(errMsg)
      }
    }

    return true
  }

  private def serializeObject(ob: Object): String = {
    // val gson = new GsonBuilder().setPrettyPrinting().create();
    val gson = new Gson();
    gson.toJson(ob)
  }

  private def serializeObject(gson: Gson, ob: Object): String = {
    gson.toJson(ob)
  }

  private def nextOption(map: OptionMap, list: List[String]): OptionMap = {
    def isSwitch(s: String) = (s(0) == '-')
    list match {
      case Nil => map
      case "--config" :: value :: tail =>
        nextOption(map ++ Map('config -> value), tail)
      case option :: tail => {
        LOG.error("%s:Unknown option:%s".format(GetCurDtTmStr, option))
        sys.exit(1)
      }
    }
  }

  private def GetCurDtTmStr: String = {
    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date(System.currentTimeMillis))
  }

  private def isDerivedFrom(clz: Class[_], clsName: String): Boolean = {
    var isIt: Boolean = false

    val interfecs = clz.getInterfaces()
    logger.debug("Interfaces => " + interfecs.length + ",isDerivedFrom: Class=>" + clsName)

    for (intf <- interfecs) {
      logger.debug("Interface:" + intf.getName())
      if (intf.getName().equals(clsName)) {
        return true
      }
    }

    return false
  }

  private def getSerializeInfo(tupleBytes: Value): String = {
    if (tupleBytes.size < _serInfoBufBytes) return ""
    val serInfoBytes = new Array[Byte](_serInfoBufBytes)
    tupleBytes.copyToArray(serInfoBytes, 0, _serInfoBufBytes)
    return (new String(serInfoBytes)).trim
  }

  private def getValueInfo(tupleBytes: Value): Array[Byte] = {
    if (tupleBytes.size < _serInfoBufBytes) return null
    val valInfoBytes = new Array[Byte](tupleBytes.size - _serInfoBufBytes)
    Array.copy(tupleBytes.toArray, _serInfoBufBytes, valInfoBytes, 0, tupleBytes.size - _serInfoBufBytes)
    valInfoBytes
  }

  private def buildObject(tupleBytes: Value, objs: Array[FatafatData]): Unit = {
    // Get first _serInfoBufBytes bytes
    if (tupleBytes.size < _serInfoBufBytes) {
      val errMsg = s"Invalid input. This has only ${tupleBytes.size} bytes data. But we are expecting serializer buffer bytes as of size ${_serInfoBufBytes}"
      logger.error(errMsg)
      throw new Exception(errMsg)
    }

    val serInfo = getSerializeInfo(tupleBytes)

    serInfo.toLowerCase match {
      case "manual" => {
        val valInfo = getValueInfo(tupleBytes)
        val datarec = new FatafatData
        datarec.DeserializeData(valInfo, this, clsLoader)
        objs(0) = datarec
      }
      case _ => {
        throw new Exception("Found un-handled Serializer Info: " + serInfo)
      }
    }
  }

  private def resolveCurretType(typName: String): Unit = {
    val typ = mdMgr.ActiveType(typName)
    if (typ == null || typ == None) {
      LOG.error("Not found valid type for " + typName)
      sys.exit(1)
    }

    if (LoadJars(typ) == false) {
      LOG.error("Failed to load dependency jars for type " + typName)
      sys.exit(1)
    }

    var isMsg = false
    var isContainer = false

    var clsName = typ.PhysicalName.trim
    if (clsName.size > 0 && clsName.charAt(clsName.size - 1) != '$') // if no $ at the end we are taking $
      clsName = clsName + "$"

    if (isMsg == false) {
      // Checking for Message
      try {
        // If required we need to enable this test
        // Convert class name into a class
        var curClz = Class.forName(clsName, true, clsLoader)

        while (curClz != null && isContainer == false) {
          isContainer = isDerivedFrom(curClz, "com.ligadata.FatafatBase.BaseContainerObj")
          if (isContainer == false)
            curClz = curClz.getSuperclass()
        }
      } catch {
        case e: Exception => {
          LOG.error("Failed to get classname:%s as message".format(clsName))
          e.printStackTrace
          sys.exit(1)
        }
      }
    }

    if (isContainer == false) {
      // Checking for container
      try {
        // If required we need to enable this test
        // Convert class name into a class
        var curClz = Class.forName(clsName, true, clsLoader)

        while (curClz != null && isMsg == false) {
          isMsg = isDerivedFrom(curClz, "com.ligadata.FatafatBase.BaseMsgObj")
          if (isMsg == false)
            curClz = curClz.getSuperclass()
        }
      } catch {
        case e: Exception => {
          LOG.error("Failed to get classname:%s as container".format(clsName))
          e.printStackTrace
          sys.exit(1)
        }
      }
    }

    if (isMsg || isContainer) {
      try {
        val mirror = ru.runtimeMirror(clsLoader)
        val module = mirror.staticModule(clsName)
        val obj = mirror.reflectModule(module)
        val objinst = obj.instance
        if (objinst.isInstanceOf[BaseMsgObj]) {
          _currentMessageObj = objinst.asInstanceOf[BaseMsgObj]
          _currentTypName = typName
          LOG.debug("Created Message Object")
        } else if (objinst.isInstanceOf[BaseContainerObj]) {
          _currentContainerObj = objinst.asInstanceOf[BaseContainerObj]
          _currentTypName = typName
          LOG.debug("Created Container Object")
        } else {
          LOG.error("Failed to instantiate message or conatiner object :" + clsName)
          sys.exit(1)
        }
      } catch {
        case e: Exception => {
          LOG.error("Failed to instantiate message or conatiner object:" + clsName + ". Reason:" + e.getCause + ". Message:" + e.getMessage())
          sys.exit(1)
        }
      }
    } else {
      LOG.error("Failed to instantiate message or conatiner object :" + clsName)
      sys.exit(1)
    }
  }

  private def GetDataStoreHandle(storeType: String, storeName: String, tableName: String, dataLocation: String): DataStore = {
    try {
      var connectinfo = new PropertyMap
      connectinfo += ("connectiontype" -> storeType)
      connectinfo += ("table" -> tableName)
      storeType match {
        case "hashmap" => {
          connectinfo += ("path" -> dataLocation)
          connectinfo += ("schema" -> tableName) // Using tableName instead of storeName here to save into different tables
          connectinfo += ("inmemory" -> "false")
          connectinfo += ("withtransaction" -> "false")
        }
        case "treemap" => {
          connectinfo += ("path" -> dataLocation)
          connectinfo += ("schema" -> tableName) // Using tableName instead of storeName here to save into different tables
          connectinfo += ("inmemory" -> "false")
          connectinfo += ("withtransaction" -> "false")
        }
        case "cassandra" => {
          connectinfo += ("hostlist" -> dataLocation)
          connectinfo += ("schema" -> storeName)
          connectinfo += ("ConsistencyLevelRead" -> "ONE")
        }
        case "hbase" => {
          connectinfo += ("hostlist" -> dataLocation)
          connectinfo += ("schema" -> storeName)
        }
        case _ => {
          throw new Exception("The database type " + storeType + " is not supported yet ")
        }
      }
      logger.debug("Getting DB Connection: " + connectinfo.mkString(","))
      KeyValueManager.Get(connectinfo)
    } catch {
      case e: Exception => {
        e.printStackTrace()
        throw new Exception(e.getMessage())
      }
    }
  }

  private def makeKey(key: String): com.ligadata.keyvaluestore.Key = {
    var k = new com.ligadata.keyvaluestore.Key
    k ++= key.getBytes("UTF8")
    k
  }

  private def loadObjFromDb(key: List[String]): FatafatData = {
    val partKeyStr = FatafatData.PrepareKey(_currentTypName, key, 0, 0)
    var objs: Array[FatafatData] = new Array[FatafatData](1)
    val buildOne = (tupleBytes: Value) => { buildObject(tupleBytes, objs) }
    try {
      _allDataDataStore.get(makeKey(partKeyStr), buildOne)
    } catch {
      case e: Exception => {
        logger.debug("1. Data not found for key:" + partKeyStr)
      }
    }
    return objs(0)
  }

  private def collectKey(key: Key, keys: ArrayBuffer[FatafatDataKey]): Unit = {
    implicit val jsonFormats = org.json4s.DefaultFormats
    val parsed_key = org.json4s.jackson.JsonMethods.parse(new String(key.toArray)).extract[FatafatDataKey]
    keys += parsed_key
  }

  private def extractData(compressionString: String, sFileName: String, partKey: List[String], primaryKey: List[String]): Unit = {
    val partKeys = ArrayBuffer[List[String]]()
    if (partKey == null || partKey.size == 0) {
      LOG.debug("Getting all Partition Keys")
      // Getting all keys if no partition key specified
      val all_keys = ArrayBuffer[FatafatDataKey]() // All keys for all tables for now
      val keyCollector = (key: Key) => { collectKey(key, all_keys) }
      _allDataDataStore.getAllKeys(keyCollector)
      // LOG.debug("Got all Keys: " + all_keys.map(k => (k.T + " -> " + k.K.mkString(","))).mkString(":"))
      val keys = all_keys.filter(k => k.T.compareTo(_currentTypName) == 0).toArray
      partKeys ++= keys.map(k => k.K)
      LOG.debug("Got all Partition Keys: " + partKeys.map(k => k.mkString(",")).mkString(":"))
    } else {
      LOG.debug("Got Partition Key: " + partKey.mkString(","))
      partKeys += partKey
    }

    val hasValidPrimaryKey = (partKey != null && primaryKey != null && partKey.size > 0 && primaryKey.size > 0)

    var os: OutputStream = null
    val gson = new Gson();
    try {
      val compString = if (compressionString == null) null else compressionString.trim

      if (compString == null || compString.size == 0) {
        os = new FileOutputStream(sFileName);
      } else if (compString.compareToIgnoreCase("gz") == 0) {
        os = new GZIPOutputStream(new FileOutputStream(sFileName))
      } else {
        throw new Exception("%s:Not yet handled other than text & GZ files".format(GetCurDtTmStr))
      }

      val ln = "\n".getBytes("UTF8")

      partKeys.foreach(partkeyval => {
        val loadedFfData = loadObjFromDb(partkeyval)
        if (loadedFfData != null) {
          if (hasValidPrimaryKey) {
            // Search for primary key match
            val v = loadedFfData.GetMessageContainerBase(primaryKey.toArray, false)
LOG.debug("Does Primarykey Found: " + (if (v == null) "false" else "true"))
            if (v != null) {
              os.write(gson.toJson(v).getBytes("UTF8"));
              os.write(ln);
            }
          } else {
            // Write the whole list
            loadedFfData.GetAllData.foreach(v => {
              os.write(gson.toJson(v).getBytes("UTF8"));
              os.write(ln);
            })
          }
        }
      })

      // Close file
      if (os != null)
        os.close
      os = null
    } catch {
      case e: Exception => {
        // Close file
        if (os != null)
          os.close
        os = null
        throw new Exception("%s:Exception. Message:%s, Reason:%s".format(GetCurDtTmStr, e.getMessage, e.getCause))
      }
    }
  }

  def main(args: Array[String]): Unit = {
    if (args.length == 0) {
      PrintUsage()
      System.exit(1)
    }

    var exitCode = 0

    try {
      LOG.debug("%s:Parsing options".format(GetCurDtTmStr))
      val options = nextOption(Map(), args.toList)
      val cfgfile = options.getOrElse('config, "").toString.trim
      if (cfgfile.size == 0) {
        LOG.error("%s:Configuration file missing".format(GetCurDtTmStr))
        sys.exit(1)
      }

      val tmpfl = new File(cfgfile)
      if (tmpfl.exists == false) {
        LOG.error("%s:Configuration file %s is not valid".format(GetCurDtTmStr, cfgfile))
        sys.exit(1)
      }

      val (loadConfigs, failStr) = Utils.loadConfiguration(cfgfile.toString, true)
      if (failStr != null && failStr.size > 0) {
        LOG.error(failStr)
        sys.exit(1)
      }

      if (loadConfigs == null) {
        LOG.error("Failed to load configurations from configuration file")
        sys.exit(1)
      }

      val nodeId = loadConfigs.getProperty("NodeId".toLowerCase, "0").replace("\"", "").trim.toInt
      if (nodeId <= 0) {
        LOG.error("Not found valid nodeId. It should be greater than 0")
        sys.exit(1)
      }

      val typName = loadConfigs.getProperty("TypeName".toLowerCase, "").replace("\"", "").trim.toLowerCase

      MetadataAPIImpl.InitMdMgrFromBootStrap(cfgfile)

      val nodeInfo = mdMgr.Nodes.getOrElse(nodeId.toString, null)
      if (nodeInfo == null) {
        LOG.error("Node %d not found in metadata".format(nodeId))
        sys.exit(1)
      }

      jarPaths = if (nodeInfo.JarPaths == null) Array[String]().toSet else nodeInfo.JarPaths.map(str => str.replace("\"", "").trim).filter(str => str.size > 0).toSet
      if (jarPaths.size == 0) {
        LOG.error("Not found valid JarPaths.")
        sys.exit(1)
      }

      val cluster = mdMgr.ClusterCfgs.getOrElse(nodeInfo.ClusterId, null)
      if (cluster == null) {
        LOG.error("Cluster not found for Node %d  & ClusterId : %s".format(nodeId, nodeInfo.ClusterId))
        sys.exit(1)
      }

      val dataStore = cluster.cfgMap.getOrElse("DataStore", null)
      if (dataStore == null) {
        LOG.error("DataStore not found for Node %d  & ClusterId : %s".format(nodeId, nodeInfo.ClusterId))
        sys.exit(1)
      }

      var dataStoreInfo: DataStoreInfo = null

      {
        implicit val jsonFormats = org.json4s.DefaultFormats
        dataStoreInfo = org.json4s.jackson.JsonMethods.parse(dataStore).extract[DataStoreInfo]
      }

      val dataStoreType = dataStoreInfo.StoreType.replace("\"", "").trim
      if (dataStoreType.size == 0) {
        LOG.error("Not found valid DataStoreType.")
        sys.exit(1)
      }

      val dataSchemaName = dataStoreInfo.SchemaName.replace("\"", "").trim
      if (dataSchemaName.size == 0) {
        LOG.error("Not found valid DataSchemaName.")
        sys.exit(1)
      }

      val dataLocation = dataStoreInfo.Location.replace("\"", "").trim
      if (dataLocation.size == 0) {
        LOG.error("Not found valid DataLocation.")
        sys.exit(1)
      }

      resolveCurretType(typName)

      _allDataDataStore = GetDataStoreHandle(dataStoreType, dataSchemaName, "AllData", dataLocation)

      val partKey = loadConfigs.getProperty("PartitionKey".toLowerCase, "").replace("\"", "").trim.split(",").map(_.trim.toLowerCase).filter(_.size > 0).toList
      val primaryKey = loadConfigs.getProperty("PrimaryKey".toLowerCase, "").replace("\"", "").trim.split(",").map(_.trim).filter(_.size > 0).toList

      val flName = loadConfigs.getProperty("OutputFile".toLowerCase, "").replace("\"", "").trim
      val compression = loadConfigs.getProperty("Compression".toLowerCase, "").replace("\"", "").trim.toLowerCase

      extractData(compression, flName, partKey, primaryKey)

      exitCode = 0
    } catch {
      case e: Exception => {
        LOG.error("%s:Failed to extract data with exception. Reason:%s, Message:%s".format(GetCurDtTmStr, e.getCause, e.getMessage))
        exitCode = 1
      }
    } finally {
      if (_allDataDataStore != null)
        _allDataDataStore.Shutdown
      _allDataDataStore = null
    }

    sys.exit(exitCode)
  }
}
