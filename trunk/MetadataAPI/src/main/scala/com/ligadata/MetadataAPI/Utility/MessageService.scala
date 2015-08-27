package com.ligadata.MetadataAPI.Utility

import java.io.File

import com.ligadata.MetadataAPI.{MetadataAPIOutputMsg, MetadataAPIImpl}

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import org.apache.log4j._

/**
 * Created by dhaval on 8/7/15.
 */
object MessageService {
  private val userid: Option[String] = Some("metadataapi")
  val loggerName = this.getClass.getName
  lazy val logger = Logger.getLogger(loggerName)

  def addMessage(input: String): String = {
    var response = ""
    var msgFileDir: String = ""
    //val gitMsgFile = "https://raw.githubusercontent.com/ligadata-dhaval/Kamanja/master/HelloWorld_Msg_Def.json"
    if (input == "") {
      msgFileDir = MetadataAPIImpl.GetMetadataAPIConfig.getProperty("MESSAGE_FILES_DIR")
      if (msgFileDir == null) {
        response = "MESSAGE_FILES_DIR property missing in the metadata API configuration"
      } else {
        //verify the directory where messages can be present
        IsValidDir(msgFileDir) match {
          case true => {
            //get all files with json extension
            val messages: Array[File] = new java.io.File(msgFileDir).listFiles.filter(_.getName.endsWith(".json"))
            messages.length match {
              case 0 => {
                println("Messages not found at " + msgFileDir)
                "Messages not found at " + msgFileDir
              }
              case option => {
                val messageDefs = getUserInputFromMainMenu(messages)
                for (messageDef <- messageDefs) {
                  response += MetadataAPIImpl.AddContainer(messageDef.toString, "JSON", userid)
                }
              }
            }
          }
          case false => {
            //println("Message directory is invalid.")
            response = "Message directory is invalid."
          }
        }
      }
    } else {
      //input provided
      var message = new File(input.toString)
      val messageDef = Source.fromFile(message).mkString
      response = MetadataAPIImpl.AddContainer(messageDef, "JSON", userid)
    }
    //Got the message. Now add them
    response
  }

  def getAllMessages: String = {
    var response = ""
    try {
      val messageKeys: Array[String] = MetadataAPIImpl GetAllMessagesFromCache(true, userid)
      if (messageKeys.length == 0) {
        response = "Sorry, No messages are available in the Metadata"
      } else {
        var srno = 0
        println("List of messages:")
        for (messageKey <- messageKeys) {
          //srno += 1
          //println("[" + srno + "] " + messageKey)
          response += messageKey
        }
      }
    } catch {
      case e: Exception => {
        response = e.getStackTrace.toString
      }
    }
    response
  }

  def updateMessage(input: String): String = {
    var response = ""
    var msgFileDir: String = ""
    //val gitMsgFile = "https://raw.githubusercontent.com/ligadata-dhaval/Kamanja/master/HelloWorld_Msg_Def.json"
    if (input == "") {
      msgFileDir = MetadataAPIImpl.GetMetadataAPIConfig.getProperty("MESSAGE_FILES_DIR")
      if (msgFileDir == null) {
        response = "MESSAGE_FILES_DIR property missing in the metadata API configuration"
      } else {
        //verify the directory where messages can be present
        IsValidDir(msgFileDir) match {
          case true => {
            //get all files with json extension
            val messages: Array[File] = new java.io.File(msgFileDir).listFiles.filter(_.getName.endsWith(".json"))
            messages.length match {
              case 0 => {
                println("Messages not found at " + msgFileDir)
                "Messages not found at " + msgFileDir
              }
              case option => {
                val messageDefs = getUserInputFromMainMenu(messages)
                for (messageDef <- messageDefs) {
                  response += MetadataAPIImpl.UpdateMessage(messageDef.toString, "JSON", userid)
                }
              }
            }
          }
          case false => {
            //println("Message directory is invalid.")
            response = "Message directory is invalid."
          }
        }
      }
    } else {
      //input provided
      var message = new File(input.toString)
      val messageDef = Source.fromFile(message).mkString
      response = MetadataAPIImpl.AddContainer(messageDef, "JSON", userid)
    }
    //Got the message. Now add them
    response
  }

  def removeMessage: String = {
    var response = ""
    try {
      val messageKeys = MetadataAPIImpl.GetAllMessagesFromCache(true, None)

      if (messageKeys.length == 0) {
        val errorMsg = "Sorry, No messages available, in the Metadata, to delete!"
        response = errorMsg
      }
      else {
        println("\nPick the message to be deleted from the following list: ")
        var srno = 0
        for (messageKey <- messageKeys) {
          srno += 1
          println("[" + srno + "] " + messageKey)
        }
        println("Enter your choice: ")
        val choice: Int = readInt()

        if (choice < 1 || choice > messageKeys.length) {
          val errormsg = "Invalid choice " + choice + ". Start with the main menu."
          response = errormsg
        }

        val msgKey = messageKeys(choice - 1)
        val(msgNameSpace, msgName, msgVersion) = com.ligadata.kamanja.metadata.Utils.parseNameToken(msgKey)
        val apiResult = MetadataAPIImpl.RemoveMessage(msgNameSpace, msgName, msgVersion.toLong, userid).toString
        response = apiResult
      }
    } catch {
      case e: Exception => {
        response = e.getStackTrace.toString
      }
    }
    response
  }


  def IsValidDir(dirName: String): Boolean = {
    val iFile = new File(dirName)
    if (!iFile.exists) {
      println("The File Path (" + dirName + ") is not found: ")
      false
    } else if (!iFile.isDirectory) {
      println("The File Path (" + dirName + ") is not a directory: ")
      false
    } else
      true
  }

  def   getUserInputFromMainMenu(messages: Array[File]): Array[String] = {
    var listOfMsgDef: Array[String] = Array[String]()
    var srNo = 0
    println("\nPick a Message Definition file(s) from below choices\n")
    for (message <- messages) {
      srNo += 1
      println("[" + srNo + "]" + message)
    }
    print("\nEnter your choice(If more than 1 choice, please use commas to seperate them): \n")
    val userOptions: List[Int] = Console.readLine().filter(_ != '\n').split(',').filter(ch => (ch != null && ch != "")).map(_.trim.toInt).toList
    //check if user input valid. If not exit
    for (userOption <- userOptions) {
      userOption match {
        case userOption if (1 to srNo).contains(userOption) => {
          //find the file location corresponding to the message
          var message = messages(userOption - 1)
          //process message
          val messageDef = Source.fromFile(message).mkString
          listOfMsgDef = listOfMsgDef :+ messageDef
        }
        case _ => {
          println("Unknown option: ")
        }
      }
    }
    listOfMsgDef
  }


  //OUTPUT MESSAGE
  def addOutputMessage(input: String): String = {
    var response = ""
    var msgFileDir: String = ""
    //val gitMsgFile = "https://raw.githubusercontent.com/ligadata-dhaval/Kamanja/master/HelloWorld_Msg_Def.json"
    if (input == "") {
      msgFileDir = MetadataAPIImpl.GetMetadataAPIConfig.getProperty("OUTPUTMESSAGE_FILES_DIR")
      if (msgFileDir == null) {
        response = "OUTPUTMESSAGE_FILES_DIR property missing in the metadata API configuration"
      } else {
        //verify the directory where messages can be present
        IsValidDir(msgFileDir) match {
          case true => {
            //get all files with json extension
            val messages: Array[File] = new java.io.File(msgFileDir).listFiles.filter(_.getName.endsWith(".json"))
            messages.length match {
              case 0 => {
                response="Output Messages not found at " + msgFileDir
              }
              case option => {
                val messageDefs = getUserInputFromMainMenu(messages)
                for (messageDef <- messageDefs) {
                  response = MetadataAPIOutputMsg.AddOutputMessage(messageDef, "JSON", userid)
                }
              }
            }
          }
          case false => {
            //println("Message directory is invalid.")
            response = "Output Message directory is invalid."
          }
        }
      }
    } else {
      //input provided
      var message = new File(input.toString)
      val messageDef = Source.fromFile(message).mkString
      response = MetadataAPIOutputMsg.AddOutputMessage(messageDef, "JSON", userid)
    }
    //Got the message. Now add them
    response
  }

  def updateOutputMessage(input: String): String = {
    var response = ""
    var msgFileDir: String = ""
    //val gitMsgFile = "https://raw.githubusercontent.com/ligadata-dhaval/Kamanja/master/HelloWorld_Msg_Def.json"
    if (input == "") {
      msgFileDir = MetadataAPIImpl.GetMetadataAPIConfig.getProperty("OUTPUTMESSAGE_FILES_DIR")
      if (msgFileDir == null) {
        response = "OUTPUTMESSAGE_FILES_DIR property missing in the metadata API configuration"
      } else {
        //verify the directory where messages can be present
        IsValidDir(msgFileDir) match {
          case true => {
            //get all files with json extension
            val messages: Array[File] = new java.io.File(msgFileDir).listFiles.filter(_.getName.endsWith(".json"))
            messages.length match {
              case 0 => {
                response="Output Messages not found at " + msgFileDir
              }
              case option => {
                val messageDefs = getUserInputFromMainMenu(messages)
                for (messageDef <- messageDefs) {
                  response = MetadataAPIOutputMsg.UpdateOutputMsg(messageDef, userid)
                }
              }
            }
          }
          case false => {
            //println("Message directory is invalid.")
            response = "Output Message directory is invalid."
          }
        }
      }
    } else {
      //input provided
      var message = new File(input.toString)
      val messageDef = Source.fromFile(message).mkString
      response = MetadataAPIOutputMsg.UpdateOutputMsg(messageDef, userid)
    }
    //Got the message. Now add them
    response
  }


  def removeOutputMessage: String = {
    var response = ""
    try {
      val outputMessageKeys = MetadataAPIOutputMsg.GetAllOutputMsgsFromCache(true, userid)

      if (outputMessageKeys.length == 0) {
        val errorMsg = "Sorry, No messages available, in the Metadata, to delete!"
        response = errorMsg
      }
      else {
        println("\nPick the message to be deleted from the following list: ")
        var srno = 0
        for (messageKey <- outputMessageKeys) {
          srno += 1
          println("[" + srno + "] " + messageKey)
        }
        println("Enter your choice: ")
        val choice: Int = readInt()

        if (choice < 1 || choice > outputMessageKeys.length) {
          val errormsg = "Invalid choice " + choice + ". Start with the main menu."
          response = errormsg
        }

        val msgKey = outputMessageKeys(choice - 1)
        val msgKeyTokens = msgKey.split("\\.")
        val msgNameSpace = msgKeyTokens(0)
        val msgName = msgKeyTokens(1)
        val msgVersion = msgKeyTokens(2)
        val apiResult = MetadataAPIOutputMsg.RemoveOutputMsg(msgNameSpace, msgName, msgVersion.toLong, userid).toString
        response = apiResult
      }
    } catch {
      case e: Exception => {
        response = e.getStackTrace.toString
      }
    }
    response
  }

  def getAllOutputMessages: String ={
    var response = ""
    try {
      val outputMessageKeys: Array[String] = MetadataAPIOutputMsg GetAllOutputMsgsFromCache(true,userid)
      if (outputMessageKeys.length == 0) {
        response = "Sorry, No output messages are available in the Metadata"
      } else {
        var srno = 0
        println("List of output messages:")
        for (outputMessageKey <- outputMessageKeys) {
          srno += 1
          println("[" + srno + "] " + outputMessageKey)
          response += outputMessageKey
        }
      }
    } catch {
      case e: Exception => {
        response = e.getStackTrace.toString
      }
    }
    response
  }
}
