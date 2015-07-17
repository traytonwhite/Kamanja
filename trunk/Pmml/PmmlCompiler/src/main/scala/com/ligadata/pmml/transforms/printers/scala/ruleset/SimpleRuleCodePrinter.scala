package com.ligadata.pmml.transforms.printers.scala.ruleset

import scala.collection.mutable._
import scala.math._
import scala.collection.immutable.StringLike
import scala.util.control.Breaks._
import com.ligadata.pmml.runtime._
import org.apache.log4j.Logger
import com.ligadata.fatafat.metadata._
import com.ligadata.pmml.compiler._
import com.ligadata.pmml.support._
import com.ligadata.pmml.traits._
import com.ligadata.pmml.syntaxtree.cooked.common._
import com.ligadata.pmml.transforms.printers.scala.common._

class SimpleRuleCodePrinter(ctx : PmmlContext) extends CodePrinter with com.ligadata.pmml.compiler.LogTrait {

	/**
	 *  Answer a string (code representation) for the supplied node.
	 *  @param node the PmmlExecNode
	 *  @param the CodePrinterDispatch to use should recursion to child nodes be required.
 	 *  @param the kind of code fragment to generate...any 
 	 *   	{VARDECL, VALDECL, FUNCCALL, DERIVEDCLASS, RULECLASS, RULESETCLASS , MININGFIELD, MAPVALUE, AGGREGATE, USERFUNCTION}
	 *  @order the traversalOrder to traverse this node...any {INORDER, PREORDER, POSTORDER} 
	 *  
	 *  @return some string representation of this node
	 */
	def print(node : Option[PmmlExecNode]
			, generator : CodePrinterDispatch
			, kind : CodeFragment.Kind
			, traversalOrder : Traversal.Order) : String = {

		val xnode : xSimpleRule = node match {
			case Some(node) => {
				if (node.isInstanceOf[xSimpleRule]) node.asInstanceOf[xSimpleRule] else null
			}
			case _ => null
		}

		val printThis = if (xnode != null) {
			codeGenerator(xnode, generator, kind, traversalOrder)
		} else {
			if (node != null) {
				PmmlError.logError(ctx, s"For ${xnode.qName}, expecting an xSimpleRule... got a ${xnode.getClass.getName}... check CodePrinter dispatch map initialization")
			}
			""
		}
		printThis
	}
	

	private def codeGenerator(node : xSimpleRule
							, generator : CodePrinterDispatch
							, kind : CodeFragment.Kind
							, traversalOrder : Traversal.Order) : String = 	{

		val simpRule : String = traversalOrder match {
			case Traversal.INORDER => { "" }
			case Traversal.POSTORDER => { "" }
			case Traversal.PREORDER => {
				kind match {
					case CodeFragment.RULECLASS => {
						NodePrinterHelpers.simpleRuleHelper(node, ctx, generator, kind, traversalOrder)
					}
					case CodeFragment.MININGFIELD => {
						/** 
						 *  Ignore this one. The visiting method will pass this down to the MiningSchema and the RuleSet children and their children. 
						 *  It only is meaningful in the MiningSchema path
						 */
						""
					}
					case _ => { 
						val kindStr : String = kind.toString
						PmmlError.logError(ctx, s"SimpleRule node - unsupported CodeFragment.Kind - $kindStr") 
						""
					}
				}
			}
		}
		simpRule
	}
}

