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
package com.stratio.crossdata.streaming.test

import com.stratio.crossdata.streaming.constants.ApplicationConstants._
import org.apache.spark.sql.crossdata.XDContext._
import org.apache.spark.sql.crossdata.models._

import scala.language.postfixOps
import scala.util.Random

trait CommonValues {

  /**
   * Kafka Options
   */
  val ConsumerHost = "localhost"
  val ProducerHost = "localhost"
  val HostStream = "127.0.0.1"
  val ConsumerPort = "2181"
  val ProducerPort = "9042"
  val TopicTest = "topicTest"

  val GroupId = "crossdatagroup"
  val PartitionOutputEmpty = None
  val PartitionOutput = Some("1")
  val additionalOptionsEmpty = Map.empty[String, String]
  val additionalOptionsStream = Map("auto.offset.reset" -> "smallest", "batchSize" -> "100")
  val StorageLevel = "MEMORY_ONLY_SER"
  val StorageStreamLevel = "MEMORY_ONLY"
  val connectionHostModel = ConnectionHostModel(ConsumerHost, ConsumerPort, ProducerHost, ProducerPort)
  val topicModel = TopicModel(TopicTest)

  val kafkaOptionsModel = KafkaOptionsModel(Seq(connectionHostModel),
    Seq(topicModel),
    GroupId,
    PartitionOutputEmpty,
    additionalOptionsEmpty,
    StorageLevel
  )
  val kafkaOptionsModelEmptyConnection = KafkaOptionsModel(Seq(),
    Seq(topicModel),
    GroupId,
    PartitionOutputEmpty,
    additionalOptionsEmpty,
    StorageLevel
  )

  val kafkaOptionsModelEmptyTopics = KafkaOptionsModel(Seq(connectionHostModel),
    Seq(),
    s"$GroupId-${Random.nextInt(10000)}",
    PartitionOutputEmpty,
    additionalOptionsEmpty,
    StorageLevel
  )
  val kafkaStreamModel = KafkaOptionsModel(Seq(connectionHostModel),
    Seq(topicModel),
    GroupId,
    PartitionOutputEmpty,
    additionalOptionsStream,
    StorageStreamLevel
  )

  val zookeeperConfEmpty = Map.empty[String, String]
  val zookeeperConfError = Map("a" -> "c", "a.b" -> "c")

  val TableName = "tabletest"
  val AliasName = "alias"
  val Sql = s"select * from $TableName"
  val queryModel = EphemeralQueryModel(TableName, Sql, AliasName)
  val queryOptionsModel = EphemeralQueryModel(TableName, Sql, AliasName, 5, Map("option" -> "value"))

  val ephemeralOptionsEmptySparkOptions = EphemeralOptionsModel(
    kafkaOptionsModel,
    EphemeralOptionsModel.DefaultAtomicWindow,
    EphemeralOptionsModel.DefaultMaxWindow,
    EphemeralOutputFormat.ROW,
    s"${EphemeralOptionsModel.DefaultCheckpointDirectory}/$TableName",
    Map.empty
  )
  val ephemeralOptionsWithSparkOptions = EphemeralOptionsModel(
    kafkaOptionsModel,
    EphemeralOptionsModel.DefaultAtomicWindow,
    EphemeralOptionsModel.DefaultMaxWindow,
    EphemeralOutputFormat.ROW,
    s"${EphemeralOptionsModel.DefaultCheckpointDirectory}/$TableName",
    Map("spark.defaultParallelism" -> "50")
  )
  val ephemeralOptionsWithSparkOptionsPrefix = EphemeralOptionsModel(
    kafkaOptionsModel,
    EphemeralOptionsModel.DefaultAtomicWindow,
    EphemeralOptionsModel.DefaultMaxWindow,
    EphemeralOutputFormat.ROW,
    s"${EphemeralOptionsModel.DefaultCheckpointDirectory}/$TableName",
    Map("defaultParallelism" -> "50")
  )
  val ephemeralOptionsStreamKafka = EphemeralOptionsModel(
    kafkaStreamModel,
    EphemeralOptionsModel.DefaultAtomicWindow,
    EphemeralOptionsModel.DefaultMaxWindow,
    EphemeralOutputFormat.ROW,
    s"${EphemeralOptionsModel.DefaultCheckpointDirectory}/$TableName",
    Map.empty
  )
  val ephemeralTableModelWithoutSparkOptions = EphemeralTableModel(TableName, ephemeralOptionsEmptySparkOptions)
  val ephemeralTableModelStreamKafkaOptions = EphemeralTableModel(TableName, ephemeralOptionsStreamKafka)
  val ephemeralTableModelWithSparkOptions = EphemeralTableModel(TableName, ephemeralOptionsWithSparkOptions)
  val ephemeralTableModelWithSparkOptionsPrefix = EphemeralTableModel(TableName, ephemeralOptionsWithSparkOptionsPrefix)

  /**
   * Select query
   */
  val TableNameSelect = "tabletestselect"
  val TopicTestSelect = "topictestselect"
  val AliasNameSelect = "aliasselect"
  val SqlSelect = s"select * from $TableNameSelect"
  val querySelectModel = EphemeralQueryModel(TableNameSelect, SqlSelect, AliasNameSelect)
  val topicModelSelect = TopicModel(TopicTestSelect)
  val kafkaStreamModelSelect = KafkaOptionsModel(Seq(connectionHostModel),
    Seq(topicModelSelect),
    GroupId,
    PartitionOutputEmpty,
    additionalOptionsStream,
    StorageStreamLevel
  )
  val ephemeralOptionsStreamKafkaSelect = EphemeralOptionsModel(
    kafkaStreamModelSelect,
    EphemeralOptionsModel.DefaultAtomicWindow,
    EphemeralOptionsModel.DefaultMaxWindow,
    EphemeralOutputFormat.ROW,
    s"${EphemeralOptionsModel.DefaultCheckpointDirectory}/$TableNameSelect",
    Map.empty
  )
  val ephemeralTableModelStreamKafkaOptionsSelect =
    EphemeralTableModel(TableNameSelect, ephemeralOptionsStreamKafkaSelect)

  /**
   * Projected query
   */
  val TableNameProject = "tabletestproject"
  val TopicTestProject = "topicTestproject"
  val AliasNameProject = "aliasproject"
  val SqlProjected = s"select name from $TableNameProject"
  val queryProjectedModel = EphemeralQueryModel(TableNameProject, SqlProjected, AliasNameProject)
  val topicModelProject = TopicModel(TopicTestProject)
  val kafkaStreamModelProject = KafkaOptionsModel(Seq(connectionHostModel),
    Seq(topicModelProject),
    GroupId,
    PartitionOutputEmpty,
    additionalOptionsStream,
    StorageStreamLevel
  )
  val ephemeralOptionsStreamKafkaProject = EphemeralOptionsModel(
    kafkaStreamModelProject,
    EphemeralOptionsModel.DefaultAtomicWindow,
    EphemeralOptionsModel.DefaultMaxWindow,
    EphemeralOutputFormat.ROW,
    s"${EphemeralOptionsModel.DefaultCheckpointDirectory}/$TableNameProject",
    Map.empty
  )
  val ephemeralTableModelStreamKafkaOptionsProject =
    EphemeralTableModel(TableNameProject, ephemeralOptionsStreamKafkaProject)

  def parseZookeeperCatalogConfig(zookeeperConf: Map[String, String]): Map[String, String] = {
    Map(CatalogClassConfigKey -> ZookeeperClass) ++
      Map(StreamingCatalogClassConfigKey -> ZookeeperClass) ++
      zookeeperConf.map { case (key, value) =>
        s"$CatalogConfigKey.$ZookeeperPrefixName.$key" -> value
      } ++
      zookeeperConf.map { case (key, value) =>
        s"$StreamingConfigKey.$CatalogConfigKey.$ZookeeperPrefixName.$key" -> value
      }
  }
}
