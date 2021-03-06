package com.recommendation.core

import org.apache.spark.SparkContext._
import org.apache.spark.mllib.linalg.distributed.{ CoordinateMatrix, MatrixEntry }
import org.apache.spark.{ Logging, SparkConf, SparkContext }

import scopt.OptionParser

case class BuyerAffinity(userId:Long, itemId:Long, affinity:Double)

  object CollaborativeFiltering extends Logging {
  case class Params(inputFile: String = null, choice: Double = 0.0)

  def main(args: Array[String]) {
    val defaultParams = Params()

    val parser = new OptionParser[Params]("Collaborative Filtering") {
      head ("A basic item-item Filter")
      opt[String]("input_file")
        .required ()
        .text (s"input file, one row per line<userId\titemId>, tab-separated")
        .action ((x, c) => c.copy (inputFile = x))
      opt[Double]("threshold")
        .text (s"threshold similarity:  trade-off computation vs quality estimate")
        .action ((x, c) => c.copy (choice = x))
      note (
        """
          |Usage:
          | The current implementation supports two forms of Collaborative Filtering
          | 1. Brute Force
          | 2. Similarity using the DIMSUM sampling algorithm
          | Reference: http://arxiv.org/pdf/1206.2082v4.pdf
          |
          | .spark-submit --num-executors 1 --executor-cores 1 --executor-memory 1g --driver-memory 2g
          | --class com.recommendation.core.CollaborativeFiltering --master local[*]
          | user-item-0.0.1-shaded.jar --input_file ../dataset.txt --threshold 0.5
        """.stripMargin)
    }

    parser.parse (args, defaultParams).map { params =>
      run (params)
    } getOrElse {
      System.exit (1)
    }
  }

  def run(params: Params) {
    val sc = new SparkContext (new SparkConf ().setAppName ("Collaborative-Filtering(User-Item)"))
    logInfo(s"File used:${params.inputFile}")

    val data = sc.textFile(params.inputFile)
    data.cache()
    logDebug("Stats about the data:")
    logDebug(s"Number of lines read: ${data.count()}")

    val userToItem = data.map(_.split("\t").toSeq)

    val userToItemAffinity = userToItem.map(x => (x(0),x(1))).map(y => (y,1)).reduceByKey(_ + _).map {
      case ((userId, itemId), affinityScore) => BuyerAffinity (userId.toLong, itemId.toLong, affinityScore)
    }

    val userItemMatrix = new CoordinateMatrix(userToItemAffinity.map {
      case BuyerAffinity(userId, itemId, affinity) => MatrixEntry(userId, itemId, affinity)
    })

    logDebug("Computing item-item Similarity...")
    val itemSimilarities = if(params.choice > 0.0) userItemMatrix.toRowMatrix.columnSimilarities(params.choice)
    else userItemMatrix.toRowMatrix.columnSimilarities()

    val result = itemSimilarities.entries.map {
      case MatrixEntry(item1, item2, cosineSimilarity) => ((item1, item2), cosineSimilarity)
    }

    logDebug("Similarities computed...")
    // Display the results
    result.foreach{case(items, similarityScore) => println(items + "\t" + similarityScore)}
  }

}