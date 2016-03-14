/**
Licensed to Elasticsearch under one or more contributor
license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright
ownership. Elasticsearch licenses this file to you under
the Apache License, Version 2.0 (the "License"); you may
not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.

Modifications and adaptations - Copyright (C) 2015 Stratio (http://stratio.com)
*/
package com.stratio.crossdata.connector.elasticsearch

import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.IndexType
import com.sksamuel.elastic4s.mappings._
import com.stratio.crossdata.connector.TableInventory.Table
import com.stratio.crossdata.connector.{TableInventory, TableManipulation}

import org.apache.spark.sql.SaveMode.{Append, ErrorIfExists, Ignore, Overwrite}
import org.apache.spark.sql.sources.{BaseRelation, CreatableRelationProvider, DataSourceRegister, RelationProvider, SchemaRelationProvider}
import org.apache.spark.sql.types.{BooleanType, DateType, DoubleType, FloatType, IntegerType, LongType, StringType, StructType}
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode}
import org.elasticsearch.hadoop.EsHadoopIllegalStateException
import org.elasticsearch.hadoop.cfg.ConfigurationOptions._
import org.elasticsearch.spark.sql.ElasticSearchXDRelation


object DefaultSource{
  val DATA_SOURCE_PUSH_DOWN: String = "es.internal.spark.sql.pushdown"
  val DATA_SOURCE_PUSH_DOWN_STRICT: String = "es.internal.spark.sql.pushdown.strict"
  val ElasticNativePort = "es.nativePort"
  val ElasticCluster = "es.cluster"
  val ElasticIndex = "es.index"
}

/**
 * This class is used by Spark to create a new  [[ElasticSearchXDRelation]]
 */
class DefaultSource extends RelationProvider with SchemaRelationProvider
                                              with CreatableRelationProvider
                                              with TableInventory
                                              with DataSourceRegister
                                              with TableManipulation  {

  import DefaultSource._

  override def shortName(): String = "elasticsearch"

  override def createRelation(@transient sqlContext: SQLContext, parameters: Map[String, String]): BaseRelation = {
    new ElasticSearchXDRelation(params(parameters), sqlContext)
  }

  override def createRelation(@transient sqlContext: SQLContext, parameters: Map[String, String], schema: StructType): BaseRelation = {
    new ElasticSearchXDRelation(params(parameters), sqlContext, Some(schema))
  }

  override def createRelation(@transient sqlContext: SQLContext, mode: SaveMode, parameters: Map[String, String],
                              data: DataFrame): BaseRelation = {

    val relation = new ElasticSearchXDRelation(params(parameters), sqlContext, Some(data.schema))
    mode match {
      case Append => relation.insert(data, false)
      case Overwrite => relation.insert(data, true)
      case ErrorIfExists => {
        if (relation.isEmpty()) relation.insert(data, false)
        else throw new EsHadoopIllegalStateException(s"Index ${relation.cfg.getResourceWrite} already exists")
      }
      case Ignore => if (relation.isEmpty()) {
        relation.insert(data, false)
      }
    }
    relation
  }

  /**
   * Validates the input parameters, defined in https://www.elastic.co/guide/en/elasticsearch/hadoop/current/configuration.html
   * @param parameters a Map with the configurations parameters
   * @return the validated map.
   */
  private def params(parameters: Map[String, String]) = {
    // . seems to be problematic when specifying the options
    val params = parameters.map { case (k, v) => (k.replace('_', '.'), v) }.map { case (k, v) =>
      if (k.startsWith("es.")) (k, v)
      else if (k == "path") ("es.resource", v)
      else if (k == "pushdown") (DATA_SOURCE_PUSH_DOWN, v)
      else if (k == "strict") (DATA_SOURCE_PUSH_DOWN_STRICT, v)
      else ("es." + k, v)
    }
    //TODO Validate required parameters


    params
  }

  /**
   * @inheritdoc
   */
  override def generateConnectorOpts(item: Table, userOpts: Map[String, String]): Map[String, String] = Map(
    ES_RESOURCE -> s"${item.database.get}/${item.tableName}"
  ) ++ userOpts

  /**
   * @inheritdoc
   */
  override def listTables(context: SQLContext, options: Map[String, String]): Seq[Table] = {

    Seq(ElasticCluster).foreach { opName =>
      if (!options.contains(opName)) sys.error( s"""Option "$opName" is mandatory for IMPORT TABLES""")
    }

    ElasticSearchConnectionUtils.listTypes(params(options))
  }

  override def createExternalTable(context: SQLContext,
                                   tableName: String,
                                   databaseName: Option[String],
                                   schema: StructType,
                                   options: Map[String, String]): Option[Table] = {

    val (index, typeName) = ElasticSearchConnectionUtils.extractIndexAndType(options).orElse(databaseName.map((_, tableName))).
      getOrElse(throw new RuntimeException(s"$ES_RESOURCE is required when running CREATE EXTERNAL TABLE"))


    val elasticSchema = schema.map { field =>
      field.dataType match {
        case IntegerType => new IntegerFieldDefinition(field.name)
        case StringType => new StringFieldDefinition(field.name)
        case DateType => new DateFieldDefinition(field.name)
        case BooleanType => new BooleanFieldDefinition(field.name)
        case DoubleType => new DoubleFieldDefinition(field.name)
        case LongType => new StringFieldDefinition(field.name)
        case FloatType => new FloatFieldDefinition(field.name)
      }
    }

    val indexType = IndexType(index, typeName)
    try {
      val client = ElasticSearchConnectionUtils.buildClient(options)
      client.execute {
        put.mapping(indexType) as elasticSchema
      }
      Option(Table(typeName, Option(index), Option(schema)))
    } catch {
      case e: Exception =>
        sys.error(e.getMessage)
        None
    }
  }

}
