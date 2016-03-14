/*
 * Copyright 2014-2015, DataStax, Inc.
 * Modifications and adaptations - Copyright (C) 2015 Stratio (http://stratio.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.stratio.crossdata.connector.cassandra


import java.util.Collection

import com.datastax.driver.core.{KeyspaceMetadata, TableMetadata}
import com.datastax.spark.connector.cql.{CassandraConnector, CassandraConnectorConf}
import com.datastax.spark.connector.rdd.ReadConf
import com.datastax.spark.connector.util.ConfigParameter
import com.datastax.spark.connector.writer.WriteConf
import com.stratio.crossdata.connector.FunctionInventory.UDF
import com.stratio.crossdata.connector.TableInventory.Table
import com.stratio.crossdata.connector.cassandra.DefaultSource._
import com.stratio.crossdata.connector.cassandra.statements.{CreateKeyspaceStatement, CreateTableStatement}
import com.stratio.crossdata.connector.{FunctionInventory, TableInventory, TableManipulation}
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode}
import org.apache.spark.sql.SaveMode.{Append, ErrorIfExists, Ignore, Overwrite}
import org.apache.spark.sql.cassandra.{CassandraSourceOptions, CassandraSourceRelation, CassandraXDSourceRelation, DefaultSource => CassandraConnectorDS, TableRef}
import org.apache.spark.sql.sources.{BaseRelation, DataSourceRegister}
import org.apache.spark.sql.types.{DataTypes, StringType, StructField, StructType}

import scala.collection.mutable



/**
 * Cassandra data source extends [[org.apache.spark.sql.sources.RelationProvider]], [[org.apache.spark.sql.sources.SchemaRelationProvider]]
 * and [[org.apache.spark.sql.sources.CreatableRelationProvider]].
 *
 * It's used internally by Spark SQL to create Relation for a table which specifies the Cassandra data source
 * e.g.
 *
 *      CREATE TEMPORARY TABLE tmpTable
 *      USING org.apache.spark.sql.cassandra
 *      OPTIONS (
 *       table "table",
 *       keyspace "keyspace",
 *       cluster "test_cluster",
 *       pushdown "true",
 *       spark_cassandra_input_page_row_size "10",
 *       spark_cassandra_output_consistency_level "ONE",
 *       spark_cassandra_connection_timeout_ms "1000"
 *      )
 */
class DefaultSource extends CassandraConnectorDS with TableInventory with FunctionInventory with DataSourceRegister with TableManipulation {

  override def shortName(): String = "cassandra"

  /**
   * Creates a new relation for a cassandra table.
   * The parameters map stores table level data. User can specify vale for following keys
   *
   *    table        -- table name, required
   *    keyspace       -- keyspace name, required
   *    cluster        -- cluster name, optional, default name is "default"
   *    pushdown      -- true/false, optional, default is true
   *    Cassandra connection settings  -- optional, e.g. spark_cassandra_connection_timeout_ms
   *    Cassandra Read Settings        -- optional, e.g. spark_cassandra_input_page_row_size
   *    Cassandra Write settings       -- optional, e.g. spark_cassandra_output_consistency_level
   *
   * When push_down is true, some filters are pushed down to CQL.
   *
   */
  override def createRelation(sqlContext: SQLContext,
                              parameters: Map[String, String]): BaseRelation = {

    val (tableRef, options) = tableRefAndOptions(parameters)
    CassandraXDSourceRelation(tableRef, sqlContext, options)
  }

  /**
   * Creates a new relation for a cassandra table given table, keyspace, cluster and push_down
   * as parameters and explicitly pass schema [[StructType]] as a parameter
   */
  override def createRelation(sqlContext: SQLContext,
                              parameters: Map[String, String],
                              schema: StructType): BaseRelation = {

    val (tableRef, options) = tableRefAndOptions(parameters)
    CassandraXDSourceRelation(tableRef, sqlContext, options, Option(schema))
  }

  /**
   * Creates a new relation for a cassandra table given table, keyspace, cluster, push_down and schema
   * as parameters. It saves the data to the Cassandra table depends on [[SaveMode]]
   */
  override def createRelation(sqlContext: SQLContext,
                              mode: SaveMode,
                              parameters: Map[String, String],
                              data: DataFrame): BaseRelation = {

    val (tableRef, options) = tableRefAndOptions(parameters)
    val table = CassandraXDSourceRelation(tableRef, sqlContext, options)

    mode match {
      case Append => table.insert(data, overwrite = false)
      case Overwrite => table.insert(data, overwrite = true)
      case ErrorIfExists =>
        if (table.buildScan().isEmpty()) {
          table.insert(data, overwrite = false)
        } else {
          throw new UnsupportedOperationException("'Writing to a non-empty Cassandra Table is not allowed.'")
        }
      case Ignore =>
        if (table.buildScan().isEmpty()) {
          table.insert(data, overwrite = false)
        }
    }

    CassandraXDSourceRelation(tableRef, sqlContext, options)
  }



  override def nativeBuiltinFunctions: Seq[UDF] = {
    //TODO: Complete the built-in function inventory
    Seq(
      UDF("now", None, StructType(Nil), StringType),
      UDF("dateOf", None, StructType(StructField("date", StringType, false)::Nil), DataTypes.TimestampType),
      UDF("unixTimestampOf", None, StructType(StructField("date", StringType, false)::Nil), DataTypes.LongType)
    )

  }

  override def createExternalTable(context: SQLContext,
                                   tableName: String,
                                   databaseName: Option[String],
                                   schema: StructType,
                                   options: Map[String, String]): Option[Table] = {
    val keyspace: String = options.get(CassandraDataSourceKeyspaceNameProperty).orElse(databaseName).
      getOrElse(throw new RuntimeException(s"$CassandraDataSourceKeyspaceNameProperty required when use CREATE EXTERNAL TABLE command"))

    val table: String = options.getOrElse(CassandraDataSourceTableNameProperty, tableName)

    try {
      buildCassandraConnector(context, options).withSessionDo { s =>
        if (s.getCluster.getMetadata.getKeyspace(keyspace) == null) {
          val createKeyspace = new CreateKeyspaceStatement(options)
          s.execute(createKeyspace.toString())
        }
        val stm = new CreateTableStatement(table, schema, options)
        s.execute(stm.toString())
      }
      Option(Table(table, Option(keyspace), Option(schema)))
    } catch {
      case e: IllegalArgumentException =>
        throw e
      case e: Exception =>
        sys.error(e.getMessage)
        None
    }
  }

  //-----------MetadataInventory-----------------


  import collection.JavaConversions._

  override def listTables(context: SQLContext, options: Map[String, String]): Seq[Table] = {

    if (options.contains(CassandraDataSourceTableNameProperty))
      require(options.contains(CassandraDataSourceKeyspaceNameProperty), s"$CassandraDataSourceKeyspaceNameProperty required when use $CassandraDataSourceTableNameProperty")

    buildCassandraConnector(context, options).withSessionDo { s =>
      val keyspaces = options.get(CassandraDataSourceKeyspaceNameProperty).fold(s.getCluster.getMetadata.getKeyspaces){
        keySpaceName => s.getCluster.getMetadata.getKeyspace(keySpaceName) :: Nil
      }

      val tablesIt: Iterable[Table] = for(
        ksMeta: KeyspaceMetadata <- keyspaces;
        tMeta: TableMetadata <- pickTables(ksMeta, options)) yield tableMeta2Table(tMeta)
      tablesIt.toSeq
    }
  }


  private def buildCassandraConnector(context: SQLContext, options: Map[String, String]): CassandraConnector = {

    val conParams = (CassandraDataSourceClusterNameProperty::CassandraConnectionHostProperty::Nil) map { opName =>
      if(!options.contains(opName)) sys.error(s"""Option "$opName" is mandatory for IMPORT CATALOG""")
      else options(opName)
    }
    val (clusterName, host) = (conParams zip conParams.tail) head

    val cfg: SparkConf = context.sparkContext.getConf.clone()
    for (ConfigParameter(prop, _, _, _) <- DefaultSource.confProperties;
         clusterLevelValue <- context.getAllConfs.get(s"$clusterName/$prop")) cfg.set(prop, clusterLevelValue)
    cfg.set("spark.cassandra.connection.host", host)

    CassandraConnector(cfg)
  }

  private def pickTables(ksMeta: KeyspaceMetadata, options: Map[String, String]): Collection[TableMetadata] = {
    options.get(CassandraDataSourceTableNameProperty).fold(ksMeta.getTables) { tableName =>
      ksMeta.getTable(tableName) :: Nil
    }
  }

  /**
   * @param tMeta C* Metadata for a given table
   * @return A table description obtained after translate its C* meta data.
   */
  private def tableMeta2Table(tMeta: TableMetadata): Table =
    Table(tMeta.getName, Some(tMeta.getKeyspace.getName))

  private lazy val systemTableRegex = "^system(_.+)?".r

  //Avoids importing system tables
  override def exclusionFilter(t: TableInventory.Table): Boolean =
    t.database.exists( dbName => systemTableRegex.findFirstIn(dbName).isEmpty)


  override def generateConnectorOpts(item: Table, opts: Map[String, String] = Map.empty): Map[String, String] = Map(
    CassandraDataSourceTableNameProperty -> item.tableName,
    CassandraDataSourceKeyspaceNameProperty -> item.database.get
  ) ++ opts.filterKeys(Set(CassandraConnectionHostProperty, CassandraDataSourceClusterNameProperty).contains(_))

  //------------MetadataInventory-----------------
}

object DefaultSource {
  val CassandraDataSourceTableNameProperty = "table"
  val CassandraDataSourceKeyspaceNameProperty = "keyspace"
  val CassandraDataSourceClusterNameProperty = "cluster"
  val CassandraDataSourceUserDefinedSchemaNameProperty = "schema"
  val CassandraDataSourcePushdownEnableProperty = "pushdown"
  val CassandraConnectionHostProperty = "spark_cassandra_connection_host"
  val CassandraDataSourceProviderPackageName = DefaultSource.getClass.getPackage.getName
  val CassandraDataSourceProviderClassName = CassandraDataSourceProviderPackageName + ".DefaultSource"
  val CassandraDataSourcePrimaryKeyStringProperty ="primary_key_string"
  val CassandraDataSourceKeyspaceReplicationStringProperty ="with_replication"

  /** Parse parameters into CassandraDataSourceOptions and TableRef object */
  def tableRefAndOptions(parameters: Map[String, String]): (TableRef, CassandraSourceOptions) = {
    val tableName = parameters(CassandraDataSourceTableNameProperty)
    val keyspaceName = parameters(CassandraDataSourceKeyspaceNameProperty)
    val clusterName = parameters.get(CassandraDataSourceClusterNameProperty)
    val pushdown: Boolean = parameters.getOrElse(CassandraDataSourcePushdownEnableProperty, "true").toBoolean
    val cassandraConfs = buildConfMap(parameters)

    (TableRef(tableName, keyspaceName, clusterName), CassandraSourceOptions(pushdown, cassandraConfs))
  }

  val confProperties = ReadConf.Properties ++
    WriteConf.Properties ++
    CassandraConnectorConf.Properties ++
    CassandraSourceRelation.Properties

  // Dot is not allowed in Options key for Spark SQL parsers, so convert . to _
  // Map converted property to origin property name
  // TODO check SPARK 1.4 it may be fixed
  private val propertiesMap: Map[String, String] = {
    confProperties.map { case ConfigParameter(prop, _, _, _) => (prop.replace(".", "_"), prop)} toMap
  }

  /** Construct a map stores Cassandra Conf settings from options */
  def buildConfMap(parameters: Map[String, String]): Map[String, String] = {
    val confMap = mutable.Map.empty[String, String]
    for (convertedProp <- propertiesMap.keySet) {
      val setting = parameters.get(convertedProp)
      if (setting.nonEmpty) {
        confMap += propertiesMap(convertedProp) -> setting.get
      }
    }
    confMap.toMap
  }

  /** Check whether the provider is Cassandra datasource or not */
  def cassandraSource(provider: String): Boolean = {
    provider == CassandraDataSourceProviderPackageName || provider == CassandraDataSourceProviderClassName
  }
}

