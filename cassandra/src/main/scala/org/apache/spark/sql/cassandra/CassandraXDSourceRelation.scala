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

package org.apache.spark.sql.cassandra

import java.io.IOException
import java.net.InetAddress
import java.util.UUID

import com.datastax.driver.core.Metadata
import com.datastax.spark.connector.cql.{CassandraConnector, CassandraConnectorConf, Schema}
import com.datastax.spark.connector.rdd.partitioner.{CassandraPartitionGenerator, DataSizeEstimates}
import com.datastax.spark.connector.rdd.{CassandraRDD, ReadConf}
import com.datastax.spark.connector.types.{InetType, UUIDType, VarIntType}
import com.datastax.spark.connector.util.Quote.quote
import com.datastax.spark.connector.util.{NameTools, ReflectionUtil}
import com.datastax.spark.connector.writer.{SqlRowWriter, WriteConf}
import com.datastax.spark.connector.{ColumnName, ColumnRef, FunctionCallRef, SomeColumns, _}
import com.stratio.common.utils.components.logger.impl.SparkLoggerComponent
import com.stratio.crossdata.connector.cassandra.CassandraQueryProcessor
import com.stratio.crossdata.connector.{NativeFunctionExecutor, NativeScan}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.cassandra.DataTypeConverter.toStructField
import org.apache.spark.sql.catalyst.CatalystTypeConverters
import org.apache.spark.sql.catalyst.expressions.aggregate.Count
import org.apache.spark.sql.catalyst.expressions.{Alias, AttributeReference, GenericRowWithSchema, Literal}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.crossdata.execution.{EvaluateNativeUDF, NativeUDF}
import org.apache.spark.sql.sources.{BaseRelation, Filter, InsertableRelation, PrunedFilteredScan}
import org.apache.spark.sql.types.{StructType, _}
import org.apache.spark.sql.{DataFrame, Row, SQLContext, sources}
import org.apache.spark.unsafe.types.UTF8String

/**
 * Implements [[org.apache.spark.sql.sources.BaseRelation]]]], [[org.apache.spark.sql.sources.InsertableRelation]]]]
 * and [[org.apache.spark.sql.sources.PrunedFilteredScan]]]]
 * It inserts data to and scans Cassandra table. If filterPushdown is true, it pushs down
 * some filters to CQL
 */
class CassandraXDSourceRelation(tableRef: TableRef,
                                userSpecifiedSchema: Option[StructType],
                                filterPushdown: Boolean,
                                tableSizeInBytes: Option[Long],
                                val connector: CassandraConnector,
                                readConf: ReadConf,
                                writeConf: WriteConf,
                                @transient override val sqlContext: SQLContext)
  extends BaseRelation
  with InsertableRelation
  with PrunedFilteredScan
  with NativeFunctionExecutor
  with NativeScan with SparkLoggerComponent {

  // NativeScan implementation ~~

  override def buildScan(optimizedLogicalPlan: LogicalPlan): Option[Array[Row]] = {
    logDebug(s"Processing ${optimizedLogicalPlan.toString()}")
    val queryExecutor = CassandraQueryProcessor(this, optimizedLogicalPlan)

    val toCatalyst = CatalystTypeConverters.createToCatalystConverter(optimizedLogicalPlan.schema)
    val toScala = CatalystTypeConverters.createToScalaConverter(optimizedLogicalPlan.schema)

    queryExecutor.execute() map { rows =>
      rows map { row =>
        val iRow = toCatalyst(row)
        toScala(iRow).asInstanceOf[GenericRowWithSchema]
      }
    }

  }

  override def isSupported(logicalStep: LogicalPlan, wholeLogicalPlan: LogicalPlan): Boolean = logicalStep match {
    case ln: LeafNode => true // TODO leafNode == LogicalRelation(xdSourceRelation)
    case un: UnaryNode => un match {
      case Limit(_, _) | Project(_, _) | Filter(_, _) | EvaluateNativeUDF(_, _, _) => true
      case aggregatePlan: Aggregate => isAggregateSupported(aggregatePlan)
      case _ => false
    }
    case unsupportedLogicalPlan => log.debug(s"LogicalPlan $unsupportedLogicalPlan cannot be executed natively"); false
  }

  def isAggregateSupported(aggregateLogicalPlan: Aggregate): Boolean = aggregateLogicalPlan match {
    case Aggregate(Nil, aggregateExpressions, _) if aggregateExpressions.length == 1 =>
      aggregateExpressions.head match {
        case Alias(Count(Literal(1, _) :: Nil), _) => false // TODO Keep it unless Cassandra implement the count efficiently
        case _ => false
      }
    case _ => false
  }

  // ~~ NativeScan implementation 

  lazy val tableDef = Schema.tableFromCassandra(connector, tableRef.keyspace, tableRef.table)

  override def schema: StructType = {
    userSpecifiedSchema.getOrElse(StructType(tableDef.columns.map(toStructField)))
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    if (overwrite) {
      connector.withSessionDo {
        val keyspace = quote(tableRef.keyspace)
        val table = quote(tableRef.table)
        session => session.execute(s"TRUNCATE $keyspace.$table")
      }
    }

    implicit val rwf = SqlRowWriter.Factory
    val columns = SomeColumns(data.columns.map(x => x: ColumnRef): _*)
    data.rdd.saveToCassandra(tableRef.keyspace, tableRef.table, columns, writeConf)
  }

  override def sizeInBytes: Long = {
    // If it's not found, use SQLConf default setting
    tableSizeInBytes.getOrElse(sqlContext.conf.defaultSizeInBytes)
  }

  implicit val cassandraConnector = connector
  implicit val readconf = readConf

  lazy val baseRdd =
    sqlContext.sparkContext.cassandraTable[CassandraSQLRow](tableRef.keyspace, tableRef.table)

  def buildScan(): RDD[Row] = baseRdd.asInstanceOf[RDD[Row]]

  override def unhandledFilters(filters: Array[Filter]): Array[Filter] = filterPushdown match {
    case true => predicatePushDown(filters).handledBySpark.toArray
    case false => filters
  }

  lazy val additionalRules: Seq[CassandraPredicateRules] = {
    import CassandraSourceRelation.AdditionalCassandraPushDownRulesParam
    val sc = sqlContext.sparkContext

    /* So we can set this in testing to different values without
     making a new context check local property as well */
    val userClasses: Option[String] =
      sc.getConf.getOption(AdditionalCassandraPushDownRulesParam.name)
        .orElse(Option(sc.getLocalProperty(AdditionalCassandraPushDownRulesParam.name)))

    userClasses match {
      case Some(classes) =>
        classes
          .trim
          .split("""\s*,\s*""")
          .map(ReflectionUtil.findGlobalObject[CassandraPredicateRules])
          .reverse
      case None => AdditionalCassandraPushDownRulesParam.default
    }
  }

  private def predicatePushDown(filters: Array[Filter]) = {
    logInfo(s"Input Predicates: [${filters.mkString(", ")}]")

    /** Apply built in rules **/
    val bcpp = new BasicCassandraPredicatePushDown(filters.toSet, tableDef)
    val basicPushdown = AnalyzedPredicates(bcpp.predicatesToPushDown, bcpp.predicatesToPreserve)
    logDebug(s"Basic Rules Applied:\n$basicPushdown")

    /** Apply any user defined rules **/
    val finalPushdown =  additionalRules.foldRight(basicPushdown)(
      (rules, pushdowns) => {
        val pd = rules(pushdowns, tableDef)
        logDebug(s"Applied ${rules.getClass.getSimpleName} Pushdown Filters:\n$pd")
        pd
      }
    )

    logDebug(s"Final Pushdown filters:\n$finalPushdown")
    finalPushdown
  }


  override def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
    buildScan(requiredColumns, filters, Map.empty)
  }

  override def buildScan(requiredColumns: Array[String],
                         filters: Array[Filter],
                         udfs: Map[String, NativeUDF]): RDD[Row] = {


    val prunedRdd = maybeSelect(baseRdd, requiredColumns, udfs)
    logInfo(s"filters: ${filters.mkString(", ")}")
    val prunedFilteredRdd = {
      if (filterPushdown) {
        val pushdownFilters = predicatePushDown(filters).handledByCassandra.toArray
        logInfo(s"pushdown filters: ${pushdownFilters.toString}")
        val filteredRdd = maybePushdownFilters(prunedRdd, pushdownFilters, udfs)
        filteredRdd.asInstanceOf[RDD[Row]]
      } else {
        prunedRdd
      }
    }
    prunedFilteredRdd.asInstanceOf[RDD[Row]]
  }

  private def resolveUDFsReferences(strId: String, udfs: Map[String, NativeUDF]): Option[FunctionCallRef] =
    udfs.get(strId) map { udf =>
      val actualParams = udf.children.collect {
        case at: AttributeReference if udfs contains at.toString => Left(resolveUDFsReferences(at.toString(), udfs).get)
        case at: AttributeReference => Left(ColumnName(at.name))
        case lit: Literal => Right(lit.toString())
      }
      FunctionCallRef(udf.name, actualParams)
    }

  /** Define a type for CassandraRDD[CassandraSQLRow]. It's used by following methods */
  private type RDDType = CassandraRDD[CassandraSQLRow]

  /** Transfer selection to limit to columns specified */
  private def maybeSelect(
                           rdd: RDDType,
                           requiredColumns: Array[String],
                           udfs: Map[String, NativeUDF] = Map.empty): RDDType = {
    if (requiredColumns.nonEmpty) {
      val cols = requiredColumns.map(column => resolveUDFsReferences(column, udfs).getOrElse(column: ColumnRef))
      rdd.select(cols: _*)
    } else {
      rdd
    }
  }

  /** Push down filters to CQL query */
  private def maybePushdownFilters(rdd: RDDType,
                                   filters: Seq[Filter],
                                   udfs: Map[String, NativeUDF] = Map.empty): RDDType = {
    whereClause(filters, udfs) match {
      case (cql, values) if values.nonEmpty =>
        val resVals = values.filter(v => resolveUDFsReferences(v.toString, udfs).isEmpty)
        rdd.where(cql, resVals: _*)
      case _ => rdd
    }
  }

  /** Construct Cql clause and retrieve the values from filter */
  private def filterToCqlAndValue(filter: Any,
                                  udfs: Map[String, NativeUDF] = Map.empty): (String, Seq[Any]) = {

    def udfvalcmp(attribute: String, cmpOp: String, f: AttributeReference): (String, Seq[Any]) =
      (s"${quote(attribute)} $cmpOp ${resolveUDFsReferences(f.toString(), udfs).get.cql}", Seq.empty)

    filter match {
      case sources.EqualTo(attribute, f: AttributeReference) if udfs contains f.toString =>
        udfvalcmp(attribute, "=", f)
      case sources.EqualTo(attribute, value) =>
        (s"${quote(attribute)} = ?", Seq(toCqlValue(attribute, value)))

      case sources.LessThan(attribute, f: AttributeReference) if udfs contains f.toString =>
        udfvalcmp(attribute, "<", f)
      case sources.LessThan(attribute, value) =>
        (s"${quote(attribute)} < ?", Seq(toCqlValue(attribute, value)))

      case sources.LessThanOrEqual(attribute, f: AttributeReference) if udfs contains f.toString =>
        udfvalcmp(attribute, "<=", f)
      case sources.LessThanOrEqual(attribute, value) =>
        (s"${quote(attribute)} <= ?", Seq(toCqlValue(attribute, value)))

      case sources.GreaterThan(attribute, f: AttributeReference) if udfs contains f.toString =>
        udfvalcmp(attribute, ">", f)
      case sources.GreaterThan(attribute, value) =>
        (s"${quote(attribute)} > ?", Seq(toCqlValue(attribute, value)))

      case sources.GreaterThanOrEqual(attribute, f: AttributeReference) if udfs contains f.toString =>
        udfvalcmp(attribute, ">=", f)
      case sources.GreaterThanOrEqual(attribute, value) =>
        (s"${quote(attribute)} >= ?", Seq(toCqlValue(attribute, value)))

      case sources.In(attribute, values)                 =>
        (quote(attribute) + " IN " + values.map(_ => "?").mkString("(", ", ", ")"), toCqlValues(attribute, values))

      case _ =>
        throw new UnsupportedOperationException(
          s"It's not a valid filter $filter to be pushed down, only >, <, >=, <= and In are allowed.")
    }
  }

  private def toCqlValues(columnName: String, values: Array[Any]): Seq[Any] = {
    values.map(toCqlValue(columnName, _)).toSeq
  }

  /** If column is VarInt column, convert data to BigInteger */
  private def toCqlValue(columnName: String, value: Any): Any = {
    value match {
      case decimal: Decimal =>
        val isVarIntColumn = tableDef.columnByName(columnName).columnType == VarIntType
        if (isVarIntColumn) decimal.toJavaBigDecimal.toBigInteger else decimal
      case utf8String: UTF8String =>
        val columnType = tableDef.columnByName(columnName).columnType
        if (columnType == InetType) {
          InetAddress.getByName(utf8String.toString)
        } else if(columnType == UUIDType) {
          UUID.fromString(utf8String.toString)
        } else {
          utf8String
        }
      case other => other
    }
  }

  /** Construct where clause from pushdown filters */
  private def whereClause(pushdownFilters: Seq[Any], udfs: Map[String, NativeUDF] = Map.empty): (String, Seq[Any]) = {
    val cqlValue = pushdownFilters.map(filterToCqlAndValue(_, udfs))
    val cql = cqlValue.map(_._1).mkString(" AND ")
    val args = cqlValue.flatMap(_._2)
    (cql, args)
  }

}

//TODO buildScan => CassandraTableScanRDD[CassandraSQLRow] => fetchTokenRange


object CassandraXDSourceRelation {

  import CassandraSourceRelation._

  def apply(tableRef: TableRef,
            sqlContext: SQLContext,
            options: CassandraSourceOptions = CassandraSourceOptions(),
            schema: Option[StructType] = None): CassandraXDSourceRelation = {

    val sparkConf = sqlContext.sparkContext.getConf
    val sqlConf = sqlContext.getAllConfs
    val conf =
      consolidateConfs(sparkConf, sqlConf, tableRef, options.cassandraConfs)
    val tableSizeInBytesString = conf.getOption(CassandraSourceRelation.TableSizeInBytesParam.name)
    val cassandraConnector =
      new CassandraConnector(CassandraConnectorConf(conf))
    val tableSizeInBytes = tableSizeInBytesString match {
      case Some(size) => Option(size.toLong)
      case None =>
        val tokenFactory = CassandraPartitionGenerator.getTokenFactory(cassandraConnector)
        val dataSizeInBytes =
          new DataSizeEstimates(
            cassandraConnector,
            tableRef.keyspace,
            tableRef.table)(tokenFactory).totalDataSizeInBytes
        if (dataSizeInBytes <= 0L) {
          None
        } else {
          Option(dataSizeInBytes)
        }
    }
    val readConf = ReadConf.fromSparkConf(conf)
    val writeConf = WriteConf.fromSparkConf(conf)

    new CassandraXDSourceRelation(
      tableRef = tableRef,
      userSpecifiedSchema = schema,
      filterPushdown = options.pushdown,
      tableSizeInBytes = tableSizeInBytes,
      connector = cassandraConnector,
      readConf = readConf,
      writeConf = writeConf,
      sqlContext = sqlContext)
  }

}