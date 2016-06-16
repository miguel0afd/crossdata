/*
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
package com.stratio.crossdata.connector.cassandra

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CassandraCreateExternalTableIT extends CassandraWithSharedContext {


  "The Cassandra connector" should "execute natively create a External Table" in {

    val tableName = "newtable"

    val createTableQueryString =
      s"""|CREATE EXTERNAL TABLE $tableName (
          |id Integer,
          |name String,
          |booleanFile boolean,
          |timeTime Timestamp,
          |binaryType Binary,
          |arrayType ARRAY<STRING>,
          |mapType MAP<INT, INT>,
          |decimalType DECIMAL
          |)
          |USING $SourceProvider
          |OPTIONS (
          |keyspace '$Catalog',
          |table '$tableName',
          |cluster '$ClusterName',
          |pushdown "true",
          |spark_cassandra_connection_host '$CassandraHost',
          |primary_key_string 'id'
          |)
      """.stripMargin.replaceAll("\n", " ")
    //Experimentation
    val result = sql(createTableQueryString).collect()

    //Expectations
    val table = xdContext.table(tableName)
    table should not be null
    table.schema.fieldNames should contain ("name")

    // In case that the table didn't exist, then this operation would throw a InvalidQueryException
    val resultSet = client.get._2.execute(s"SELECT * FROM $Catalog.$tableName")

    import scala.collection.JavaConversions._

    resultSet.getColumnDefinitions.asList.map(cd => cd.getName) should contain ("name")
  }

  it should "execute natively create a External Table with no existing Keyspace" in {
    val createTableQueryString =
      s"""|CREATE EXTERNAL TABLE newkeyspace.othertable (id Integer, name String)
          |USING $SourceProvider
          |OPTIONS (
          |keyspace 'newkeyspace',
          |cluster '$ClusterName',
          |pushdown "true",
          |spark_cassandra_connection_host '$CassandraHost',
          |primary_key_string 'id',
          |with_replication "{'class' : 'SimpleStrategy', 'replication_factor' : 3}"
          |)
      """.stripMargin.replaceAll("\n", " ")

    try {
      //Experimentation
      val result = sql(createTableQueryString).collect()

      //Expectations
      val table = xdContext.table(s"newkeyspace.othertable")
      table should not be null
      table.schema.fieldNames should contain("name")
    }finally {
      //AFTER
      client.get._2.execute(s"DROP KEYSPACE newkeyspace")
    }
  }

  it should "fail execute natively create a External Table with no existing Keyspace without with_replication" in {
    val createTableQueryString =
      s"""|CREATE EXTERNAL TABLE NoKeyspaceCreatedBefore.newTable (id Integer, name String)
          |USING $SourceProvider
          |OPTIONS (
          |keyspace 'NoKeyspaceCreatedBefore',
          |cluster '$ClusterName',
          |pushdown "true",
          |spark_cassandra_connection_host '$CassandraHost',
          |primary_key_string 'id'
          |)
      """.stripMargin.replaceAll("\n", " ")
    //Experimentation

    the [IllegalArgumentException] thrownBy {
      sql(createTableQueryString).collect()
    }  should have message "requirement failed: with_replication required when use CREATE EXTERNAL TABLE command"

  }

  
}
