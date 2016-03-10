/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.kafka

import com.stratio.crossdata.streaming.kafka.KafkaInput
import com.stratio.crossdata.streaming.test.{BaseSparkStreamingXDTest, CommonValues}
import org.apache.spark.streaming.{Milliseconds, StreamingContext}
import org.apache.spark.{SparkContext, SparkConf}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

@RunWith(classOf[JUnitRunner])
class KafkaStreamIT extends BaseSparkStreamingXDTest with CommonValues {

  val sparkConf = new SparkConf().setMaster("local[2]").setAppName(this.getClass.getSimpleName)
  val sc = SparkContext.getOrCreate(sparkConf)
  var ssc: StreamingContext = _
  val kafkaTestUtils: KafkaTestUtils =  new KafkaTestUtils
  kafkaTestUtils.setup()

  after {
    if (ssc != null) {
      ssc.stop(stopSparkContext = false, stopGracefully = false)
      ssc.awaitTerminationOrTimeout(3000)
      ssc = null
    }
  }

  override def afterAll : Unit = {
    kafkaTestUtils.teardown()
  }

  test("Kafka input stream with kafkaOptionsModel from Map of values") {
    ssc = new StreamingContext(sc, Milliseconds(1000))
    val valuesToSent = Map("a" -> 5, "b" -> 3, "c" -> 10)
    kafkaTestUtils.createTopic(TopicTest)
    kafkaTestUtils.sendMessages(TopicTest, valuesToSent)

    val kafkaStreamModelZk = kafkaStreamModel.copy(connection = Seq(
      connectionHostModel.copy(consumerPort = kafkaTestUtils.zkAddress.split(":").last)
    ))
    val input = new KafkaInput(kafkaStreamModelZk)
    val stream = input.createStream(ssc)
    val result = new mutable.HashMap[String, Long]() with mutable.SynchronizedMap[String, Long]

    stream.map(_._2).countByValue().foreachRDD { rdd =>
      val ret = rdd.collect()
      ret.toMap.foreach { case (key, value) =>
        val count = result.getOrElseUpdate(key, 0) + value
        result.put(key, count)
      }
    }

    ssc.start()

    eventually(timeout(10000 milliseconds), interval(1000 milliseconds)) {
      assert(valuesToSent === result)
    }
  }

  test("Kafka input stream with kafkaOptionsModel from list of Strings") {
    ssc = new StreamingContext(sc, Milliseconds(500))
    val valuesToSent = Array("a", "b", "c")
    kafkaTestUtils.createTopic(TopicTestProject)
    kafkaTestUtils.sendMessages(TopicTestProject, valuesToSent)

    val kafkaStreamModelZk = kafkaStreamModelProject.copy(connection = Seq(
      connectionHostModel.copy(consumerPort = kafkaTestUtils.zkAddress.split(":").last)
    ))
    val input = new KafkaInput(kafkaStreamModelZk)
    val stream = input.createStream(ssc)
    val result = new mutable.MutableList[String]()

    stream.map(_._2).foreachRDD { rdd =>
      val ret = rdd.collect()
      ret.foreach(value => result.+=(value))
    }

    ssc.start()

    eventually(timeout(10000 milliseconds), interval(1000 milliseconds)) {
      assert(valuesToSent === result)
    }
  }
}
