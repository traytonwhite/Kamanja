package com.ligadata.metadataapiservice

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import akka.io.IO
import spray.routing.RequestContext
import spray.httpx.SprayJsonSupport
import spray.client.pipelining._
import scala.util.{ Success, Failure }
import com.ligadata.MetadataAPI._
import com.ligadata.Serialize._

import scala.util.control._

import org.apache.log4j._

object DeactivateObjectsService {
	case class Process(apiArgListJson: String)
}

class DeactivateObjectsService(requestContext: RequestContext) extends Actor {

  import DeactivateObjectsService._
  
  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)
  
  
  val loggerName = this.getClass.getName
  val logger = Logger.getLogger(loggerName)
  logger.setLevel(Level.TRACE);

  val APIName = "DeactivateObjects"

  def receive = {
    case Process(apiArgListJson: String) =>
      process(apiArgListJson)
      context.stop(self)
  }

  def DeactivateObjectDef(arg: MetadataApiArg): String = {
    var resultStr:String = ""
    var nameSpace = "str"
    var version = "-1"
    var formatType = "JSON"
    var apiResult:String = ""

    if( arg.NameSpace != null ){
      nameSpace = arg.NameSpace
    }
    if( arg.Version != null ){
      version = arg.Version
    }
    if( arg.FormatType != null ){
      formatType = arg.FormatType
    }

    arg.ObjectType match {
      case "Model" => {
	apiResult = MetadataAPIImpl.DeactivateModel(nameSpace,arg.Name,version.toInt)
	val (statusCode,resultData) = MetadataAPIImpl.getApiResult(apiResult)
	resultStr = resultData
      }
      case _ => {
	resultStr = "Deactivate/Dedeactivate on " + arg.ObjectType + " is not supported yet "
      }
    }
    resultStr
  }

  def process(apiArgListJson: String) = {
    
    logger.trace(APIName + ":" + apiArgListJson)

    val apiArgList = JsonSerializer.parseApiArgList(apiArgListJson)
    val arguments = apiArgList.ArgList
    var resultStr:String = ""

    if ( arguments.length > 0 ){
      var loop = new Breaks
      loop.breakable{
	arguments.foreach(arg => {
	  if(arg.ObjectType == null ){
	    resultStr = APIName + ":Error: The value of object type can't be null"
	    loop.break
	  }
	  if(arg.Name == null ){
	    resultStr = APIName + ":Error: The value of object name can't be null"
	    loop.break
	  }
	  else {
	    resultStr = resultStr + DeactivateObjectDef(arg)
	  }
	})
      }
    }
    else{
      resultStr = APIName + ":No arguments passed to the API, nothing much to do"
    }
    requestContext.complete(resultStr)
  }
}