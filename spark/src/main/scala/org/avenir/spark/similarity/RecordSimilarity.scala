/*
 * avenir-spark: Predictive analytic based on Spark
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */


package org.avenir.spark.similarity

import org.chombo.spark.common.JobConfiguration
import org.apache.spark.SparkContext
import scala.collection.JavaConverters._
import org.chombo.spark.common.Record
import org.chombo.util.BasicUtils
import org.chombo.distance.InterRecordDistance
import scala.collection.mutable.ListBuffer


/**
 * @param args
 * @return
 */
object RecordSimilarity extends JobConfiguration {
   /**
    * @param args
    * @return
    */
   def main(args: Array[String])  {
	   val appName = "recordSimilarity"
	   val Array(inputPath: String, outputPath: String, configFile: String) = getCommandLineArgs(args, 3)
	   val config = createConfig(configFile)
	   val sparkConf = createSparkConf(appName, config, false)
	   val sparkCntxt = new SparkContext(sparkConf)
	   val appConfig = config.getConfig(appName)
	   
	   //configurations
	   val fieldDelimIn = getStringParamOrElse(appConfig, "field.delim.in", ",")
	   val fieldDelimOut = getStringParamOrElse(appConfig, "field.delim.out", ",")
	   val keyFieldOrdinals = getMandatoryIntListParam(appConfig, "id.field.ordinals").asScala.toArray
	   val richAttrSchemaPath = getMandatoryStringParam(appConfig, "rich.attr.schema.path")
	   val richAttrSchema = BasicUtils.getRichAttributeSchema(richAttrSchemaPath)
	   val distAttrSchemaPath = getMandatoryStringParam(appConfig, "dist.attr.schema.path")
	   val distAttrSchema = BasicUtils.getDistanceSchema(distAttrSchemaPath)
	   val distFinder = new InterRecordDistance(richAttrSchema, distAttrSchema, fieldDelimIn)
	   val numBuckets = getIntParamOrElse(appConfig, "num.buckets", 16)
	   val buckets  = List.range(0, numBuckets)
	   val debugOn = getBooleanParamOrElse(appConfig, "debug.on", false)
	   val saveOutput = getBooleanParamOrElse(appConfig, "save.output", true)
	   
	   //read input
	   val data = sparkCntxt.textFile(inputPath)

	   //key with all bucket pairs and record as value
	   val bucketedData = data.flatMap(line => {
		   val items = line.split(fieldDelimIn, -1)
		   val keyRec = Record(items, keyFieldOrdinals)
		   val hash = keyRec.hashCode
		   val thisBucket = (if (hash < 0) -hash else hash) % numBuckets
		   var bucketId = 0
		   val bucketedRec = buckets.map(b => {
		     val bucketPairHash = if (thisBucket > b) {
		        bucketId = 0
		        thisBucket << 12 | b
		     } else { 
		        bucketId = 1
		        b << 12 | thisBucket  
		     }
		       
		     (bucketPairHash, (bucketId,line)) 
		   })
		   
		   bucketedRec
	   })
	   
	   //group by key and generate distances
	   val bucketedDistances = bucketedData.groupByKey().flatMapValues(recs => {
	     val firstBucket = recs.filter(r => r._1 == 0)
	     val secondBucket = recs.filter(r => r._1 == 1)
	     val distances = new ListBuffer[(Record, Record, Double)]()
	     firstBucket.foreach(f => {
	       val firstRec = f._2
	       val firstKey = Record(firstRec.split(fieldDelimIn, -1), keyFieldOrdinals)
	       secondBucket.foreach(s => {
	    	   val secondRec = s._2
	    	   val secondKey = Record(secondRec.split(fieldDelimIn, -1), keyFieldOrdinals)
	    	   val dist = distFinder.findDistance(firstRec, secondRec)
	    	   distances += ((firstKey, secondKey, dist))
	       })
	     })
	     distances
	   })
	   
	   //only distances
	   val distances = bucketedDistances.values
	   
	   if (debugOn) {
	     val distCol = distances.collect
	     distCol.foreach(d => {
	       println(d)
	     })
	   }	
	   
	   if (saveOutput) {
	     distances.saveAsTextFile(outputPath)
	   }
	   
   }
}