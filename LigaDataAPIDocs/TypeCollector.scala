package com.ligadata.Compiler

import scala.collection.mutable._
import scala.collection.immutable.{ Set }
import scala.math._
import scala.collection.immutable.StringLike
import scala.util.control.Breaks._
import scala.reflect.runtime.universe._
import org.apache.log4j.Logger
import com.ligadata.olep.metadata._

/**
 * TypeCollector's focus is to reason about the type system present and implied by the Pmml being processed.
 * It is used principally by the FunctionSelect and MacroSelect to collect and infer enough information to 
 * select the suitable function or function macro by those classes.
 * 
 * Type information container, FcnTypeInfo, is also defined here to organize the type information for these 
 * select classes and later their printers.
 */

object FcnTypeInfoType extends Enumeration {
	type InfoType = Value 
	val SIMPLE_FCN, ITERABLE_FCN, ITERABLE_FCN_SANS_MBRFCN = Value
}
import FcnTypeInfoType._

/**
 * 	FcnTypeInfo is the swiss army knife of type info for functions.  An instance can describe several flavors
 *  of functions in the system (see FcnTypeInfoType enumeraition above).
 *  
 *  @param typeInfoType - The flavor of function represented by the FcnTypeInfo.
 *  @param fcnDef - The metadata information of this function.  In the case of an Iterable, this is the FcnDef that
 *  	describes the outer function aka the 'iterable' function.
 *  @param argTypes - An array of triples that describe the args' type info.  This info is represented as an array of tuple
 *  	of (typeString, isContainer, BaseTypeDef), one tuple per argument.  Note that this is the "simple" representation.
 *   	The possible container qualified information that may be present for container.subcontainer.subsub...field is
 *    	stripped off of this version for convenience.  In most cases, this is sufficient to render appropriate code
 *      representation for the function.  When the function is an ITERABLE_FCN, there is only the "receiver" arg here.
 *      For the ITERABLE_FCN types, the remaining arguments are found in the mbrArgTypes (and full args in the elemFcnArgsFull).
 *  @param argTypesFull - This is an array of arrays of the type info tuples.  In this version, the full type info
 *  	for each potentially qualified argument is represented - hence the array of type info triples for each
 *   	argument.
 *  @param winningKey - The key that retrieved the metadata element defined in fcnDef. 
 *  @param mbrFcn - The metadata information for the so-called 'member' function when this function isA ITERABLE_FCN*
 *  	It is null for SIMPLE_FCN function types.
 *  @param mbrArgTypes - For ITERABLE_FCN function types, this array contains the type info triples for the leaf or "simple"
 *  	functions.  Note that if the typeInfoType is ITERABLE_FCN_SANS_MBRFCN (there is no function present... this is an
 *   	ITERABLE that doesn't contain a function... it is typically a projection.  If more than one args are present here, 
 *    	a tuple of the arguments is returned.  If not an iterable, it is null.
 *  @param elemFcnArgsFull - For ITERABLE_FCN function types, this array contains the full type info arrays for each argument 
 *  	in the function.  If not an iterable, it is null.
 *  @param elemFcnArgRange - For ITERABLE_FCN function types, this tuple describes the first and last index in the Pmml apply function
 *  	arguments.  These values are used to control the print of the argument list for the member function of ITERABLE_FCN* types.
 *   	If not an iterable, the difference between elemFcnArgRange._2 and elemFcnArgRange._1 is negative.
 * 	@param containerTypeDef - For ITERABLE_FCN function types, this is the metadata that describes the "receiver" that has the
 *  	Iterable trait behavior (i.e., the array, map, or other collection is position 1 in the argument list of the Pmml apply).
 *   	If not an iterable, it is null.
 *  @param collectionElementTypes - For ITERABLE_FCN function types, this is the metadata that describes the receiver's member type
 *  	or types (e.g., for Array[Int], this array would have the Int's metadata; for Map[String,Double], the String's and Double's 
 *   	metadata is present.  If not iterable, it is null.
 *  @param winningMbrKey - The key that retrieved the metadata element for the member function of an ITERABLE_FCN function.  If not
 *  	an iterable, it is null.
 *  @param returnTypes - This array contains the string representation of the return type of each argument that isA FUNCTION.  They are not 
 *  	currently divided into separate arrays for ITERABLE_FCN types.  The elemFcnArgRange can be used to go get the right
 *   	member function argument return type.  For any arg that is not a function, the value will be a null.
 *   
 *  This information is used by the type inference module and function printer to perform their respective duties.
 */

class FcnTypeInfo(var typeInfoType : InfoType
				, var fcnDef : FunctionDef
				, var argTypes : Array[(String,Boolean,BaseTypeDef)]
				, var argTypesFull : Array[Array[(String,Boolean,BaseTypeDef)]]
				, var winningKey : String 
				, var mbrFcn : FunctionDef = null
				, var mbrArgTypes : Array[(String,Boolean,BaseTypeDef)] = null 
				, var elemFcnArgsFull : Array[Array[(String,Boolean,BaseTypeDef)]] = null
				, var elemFcnArgRange : (Int,Int) = null
				, var containerTypeDef : ContainerTypeDef = null
				, var collectionElementTypes : Array[BaseTypeDef] = null
				, var winningMbrKey : String = null
				, var returnTypes : Array[String] = null) extends LogTrait {
  
	/** simple functions */
	def this(fcndef : FunctionDef
			, argtypes : Array[(String,Boolean,BaseTypeDef)]
			, argsFull : Array[Array[(String,Boolean,BaseTypeDef)]]
			, winningKey : String) {
		this(SIMPLE_FCN, fcndef, argtypes, argsFull, winningKey)
	}
	
	/** iterable function */
	def this(fcndef : FunctionDef
			, argtypes : Array[(String,Boolean,BaseTypeDef)]
			, argsFull : Array[Array[(String,Boolean,BaseTypeDef)]]
	    	, winningKey : String
			, mbrfcn : FunctionDef
			, mbrargtypes : Array[(String,Boolean,BaseTypeDef)]
			, elemFcnArgs : Array[Array[(String,Boolean,BaseTypeDef)]]
			, elemFcnArgRange : (Int,Int)
			, containertypedef : ContainerTypeDef
			, collelemtypes : Array[BaseTypeDef]
			, winningMbrKey : String
			, retTypes : Array[String]) {
		this((if (mbrfcn != null) ITERABLE_FCN else ITERABLE_FCN_SANS_MBRFCN)
		    , fcndef
		    , argtypes
		    , argsFull
		    , winningKey
		    , mbrfcn
		    , mbrargtypes
		    , elemFcnArgs
		    , elemFcnArgRange
		    , containertypedef
		    , collelemtypes
		    , winningMbrKey
		    , retTypes)
	}
	
	def fcnTypeInfoType : InfoType = typeInfoType
	def WinningKey(key : String) : Unit = { winningKey = key }
	def WinningKey : String = winningKey
  
}


class TypeCollector(fcnTypeInfo : FcnTypeInfo) {

	def determineReturnType(fcnNode : xApply) : String = {
		val typestring : String = if (fcnTypeInfo != null && fcnTypeInfo.fcnTypeInfoType != SIMPLE_FCN) {
			val scalaFcnName : String = PmmlTypes.scalaNameForIterableFcnName(fcnNode.function)
			val isMapFcn : Boolean = (scalaFcnName == "map")
			val typStr : String = if (fcnTypeInfo != null && fcnTypeInfo.fcnDef != null) {
				if (isMapFcn) {  
				  
					/** 
					 *  FIXME: This is clearly inadequate... groupBy et al need to be handled
					 *  
					 *  This code doesn't account for a map with no mbr function.  Fix this . 
					 *  
					 */
					val nominalCollectionName : String = ObjType.asString(fcnTypeInfo.containerTypeDef.tType)
					val elementType : String = if (fcnTypeInfo.mbrFcn != null) fcnTypeInfo.mbrFcn.returnTypeString else "Any"
					val returnType : String = s"$nominalCollectionName[$elementType]"
					returnType
				} else {
					fcnTypeInfo.fcnDef.returnTypeString
				}
			} else {
				"Any"
			}
			typStr
		} else {
			val typStr : String = if (fcnTypeInfo != null && fcnTypeInfo.fcnDef != null) {
				fcnTypeInfo.fcnDef.returnTypeString
			} else {
				"Any"
			}
			typStr
		}
		typestring
	}
}