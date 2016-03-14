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
package org.apache.spark.sql.crossdata.execution.datasources

import java.util.UUID
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.crossdata.XDContext
import org.apache.spark.sql.execution.datasources.DDLParser
import org.apache.spark.sql.types._

import scala.language.implicitConversions


class XDDdlParser(parseQuery: String => LogicalPlan, xDContext: XDContext) extends DDLParser(parseQuery) {

  protected val IMPORT = Keyword("IMPORT")
  protected val TABLES = Keyword("TABLES")
  protected val DROP = Keyword("DROP")
  protected val VIEW = Keyword("VIEW")
  protected val EXTERNAL = Keyword("EXTERNAL")
  protected val ADD =Keyword("ADD")
  protected val JAR = Keyword("JAR")
  //Streaming keywords
  protected val EPHEMERAL = Keyword("EPHEMERAL")
  protected val SHOW = Keyword("SHOW")
  protected val ALL = Keyword("ALL")
  protected val GET = Keyword("GET")
  protected val STATUS = Keyword("STATUS")
  protected val STATUSES = Keyword("STATUSES")
  protected val QUERY = Keyword("QUERY")
  protected val QUERIES = Keyword("QUERIES")
  protected val WITH = Keyword("WITH")
  protected val WINDOW = Keyword("WINDOW")
  protected val SEC = Keyword("SEC")
  protected val SECS = Keyword("SECS")
  protected val SECONDS = Keyword("SECONDS")
  protected val START = Keyword("START")
  protected val STOP = Keyword("STOP")
  protected val IN = Keyword("IN")


  override protected lazy val ddl: Parser[LogicalPlan] =

    createTable | describeTable | refreshTable | importStart | dropTable |
      createView | createExternalTable | dropView | addJar | streamingSentences

  // TODO move to StreamingDdlParser

  protected lazy val streamingSentences: Parser[LogicalPlan] =
    describeEphemeralTable | showEphemeralTables | createEphemeralTable | dropAllEphemeralQueries  | dropAllEphemeralTables |
      showEphemeralStatus | showEphemeralStatuses | startProcess | stopProcess |
      showEphemeralQueries | addEphemeralQuery | dropEphemeralQuery | dropEphemeralTable


  protected lazy val importStart: Parser[LogicalPlan] =
    IMPORT ~> TABLES ~> (USING ~> className) ~ (OPTIONS ~> options).? ^^ {
      case provider ~ ops =>
        ImportTablesUsingWithOptions(provider.asInstanceOf[String], ops.getOrElse(Map.empty))
    }

  protected lazy val dropTable: Parser[LogicalPlan] =
    DROP ~> TABLE ~> tableIdentifier ^^ {
      case tableId =>
        DropTable(tableId)
    }


  protected lazy val dropView: Parser[LogicalPlan] =
    DROP ~> VIEW ~> tableIdentifier ^^ {
      case tableId =>
        DropView(tableId)
    }

  protected lazy val createView: Parser[LogicalPlan] = {

    (CREATE ~> TEMPORARY.? <~ VIEW) ~ tableIdentifier ~ (AS ~> restInput) ^^ {
      case temp ~ viewIdentifier ~ query =>
        if (temp.isDefined)
          CreateTempView(viewIdentifier, parseQuery(query))
        else
          CreateView(viewIdentifier, parseQuery(query), query)
    }
  }

  protected lazy val createExternalTable: Parser[LogicalPlan] = {

    CREATE ~> EXTERNAL ~> TABLE ~> tableIdentifier ~ tableCols ~ (USING ~> className) ~ (OPTIONS ~> options).? ^^ {
      case tableName ~ columns ~ provider ~ opts =>
        val userSpecifiedSchema = StructType(columns)
        val options = opts.getOrElse(Map.empty[String, String])

        CreateExternalTable(tableName, userSpecifiedSchema, provider, options)
    }
  }

  protected lazy val addJar: Parser[LogicalPlan] =
    ADD ~> JAR ~> restInput ^^ {
      case jarPath =>
        AddJar(jarPath.trim)
    }

  /**
   * Streaming
   */

  protected lazy val startProcess: Parser[LogicalPlan] = {
    (START ~> tableIdentifier) ^^ {
      case table => StartProcess(table.unquotedString)
    }
  }

  protected lazy val stopProcess: Parser[LogicalPlan] = {
    (STOP ~> tableIdentifier) ^^ {
      case table => StopProcess(table.unquotedString)
    }
  }

  /**
   * Ephemeral Table Functions
   */

  protected lazy val describeEphemeralTable: Parser[LogicalPlan] = {
    (DESCRIBE ~ EPHEMERAL ~ TABLE ~> tableIdentifier) ^^ {
      case tableIdent => DescribeEphemeralTable(tableIdent)
    }
  }

  protected lazy val showEphemeralTables: Parser[LogicalPlan] = {
    (SHOW ~ EPHEMERAL ~ TABLES) ^^ {
      case operation => ShowEphemeralTables
    }
  }

  protected lazy val createEphemeralTable: Parser[LogicalPlan] = {
    (CREATE ~ EPHEMERAL ~ TABLE ~> tableIdentifier) ~ tableCols.? ~ (OPTIONS ~> options) ^^ {
      case tableIdent ~ columns ~ opts => {
        val userSpecifiedSchema = columns.flatMap(fields => Some(StructType(fields)))
        CreateEphemeralTable(tableIdent, userSpecifiedSchema, opts)
      }
    }
  }

  protected lazy val dropEphemeralTable: Parser[LogicalPlan] = {
    (DROP ~ EPHEMERAL ~ TABLE ~> tableIdentifier) ^^ {
      case tableIdent => DropEphemeralTable(tableIdent)
    }
  }

  protected lazy val dropAllEphemeralTables: Parser[LogicalPlan] = {
    (DROP ~ ALL ~ EPHEMERAL ~ TABLES) ^^ {
      case op => DropAllEphemeralTables
    }
  }

  /**
   * Ephemeral Table Status Functions
   */

  protected lazy val showEphemeralStatus: Parser[LogicalPlan] = {
    (SHOW ~ EPHEMERAL ~ STATUS ~ IN ~> tableIdentifier) ^^ {
      case tableIdent => ShowEphemeralStatus(tableIdent)
    }
  }

  protected lazy val showEphemeralStatuses: Parser[LogicalPlan] = {
    (SHOW ~ EPHEMERAL ~ STATUSES) ^^ {
      case operation => ShowAllEphemeralStatuses
    }
  }

  /**
   * Ephemeral Queries Functions
   */

  protected lazy val showEphemeralQueries: Parser[LogicalPlan] = {
    (SHOW ~ EPHEMERAL ~ QUERIES ~> ( IN ~> ident).? ) ^^ {
      case queryIdent => ShowEphemeralQueries(queryIdent)
    }
  }

  protected lazy val addEphemeralQuery: Parser[LogicalPlan] = {
    ADD.? ~ streamingSql ~ (WITH ~ WINDOW ~> numericLit <~ (SEC | SECS | SECONDS)) ~ (AS ~> ident).? ^^ {

      case addDefined ~ streamQl ~ litN ~ topIdent =>
        val queryTables: Seq[LogicalPlan] = parseQuery(streamQl).collect {
          case UnresolvedRelation(tableIdent, alias) =>
            xDContext.catalog.lookupRelation(tableIdent, alias)
        }

        val ephTables: Seq[String] = queryTables.collect{
          case StreamingRelation(ephTableName) => ephTableName
        }

        ephTables.distinct match {
          case Seq(eTableName) =>
            AddEphemeralQuery(eTableName, streamQl, topIdent.getOrElse(UUID.randomUUID().toString), new Integer(litN))
          case tableNames =>
            sys.error(s"Expected an ephemeral table within the query, but found ${tableNames.mkString(",")}")
        }
    }
  }

  protected lazy val dropEphemeralQuery: Parser[LogicalPlan] = {
    (DROP ~ EPHEMERAL ~ QUERY ~> ident) ^^ {
      case queryIdentifier => DropEphemeralQuery(queryIdentifier)
    }
  }

  protected lazy val dropAllEphemeralQueries: Parser[LogicalPlan] = {
    (DROP ~ ALL ~ EPHEMERAL ~ QUERIES ~> (IN ~> tableIdentifier).? ) ^^ {
      case tableIdent => DropAllEphemeralQueries(tableIdent.map(_.unquotedString))
    }
  }

  // Returns the select statement without the streaming info
  protected lazy val streamingSql: Parser[String] = new Parser[String] {
    def apply(in: Input): ParseResult[String] = {
      val indexOfWithWindow = in.source.toString.indexOf("WITH WINDOW")
      if (indexOfWithWindow <= 0) {
        restInput(in)
      } else {
        val streamSql = in.source.subSequence(in.offset, indexOfWithWindow).toString.trim

        def streamingInfoInput(inpt: Input): Input = {
          val startsWithWindow = inpt.source.subSequence(inpt.offset, inpt.source.length()).toString.trim.startsWith("WITH WINDOW")
          if (startsWithWindow) inpt else streamingInfoInput(inpt.rest)
        }
        Success(streamSql, streamingInfoInput(in))
      }
    }
  }

}
