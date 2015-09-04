package com.ligadata.jpmml.concrete

import com.ligadata.jpmml.deployment.{JpmmlModelManagerMapImpl, JpmmlModelManagerFileReader}
import com.ligadata.jpmml.evaluation.JpmmlEvaluator

object JpmmlModelEvaluatorConcrete extends JpmmlModelManagerFileReader with JpmmlEvaluator with JpmmlModelManagerMapImpl