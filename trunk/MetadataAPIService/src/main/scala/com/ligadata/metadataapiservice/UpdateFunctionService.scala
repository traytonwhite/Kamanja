package com.ligadata.metadataapiservice

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import akka.io.IO
import spray.routing.RequestContext
import spray.httpx.SprayJsonSupport
import spray.client.pipelining._
import scala.util.{ Success, Failure }
import com.ligadata.MetadataAPI._

object UpdateFunctionService {
  case class Process(functionJson:String)
}

class UpdateFunctionService(requestContext: RequestContext) extends Actor {

  import UpdateFunctionService._
  
  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)
  
  def receive = {
    case Process(functionJson) =>
      process(functionJson)
      context.stop(self)
  }
  
  def process(functionJson:String) = {
    
    log.info("Requesting UpdateFunction {}",functionJson)
    
    val apiResult = MetadataAPIImpl.UpdateFunctions(functionJson,"JSON")
    
    requestContext.complete(apiResult)
  }
}