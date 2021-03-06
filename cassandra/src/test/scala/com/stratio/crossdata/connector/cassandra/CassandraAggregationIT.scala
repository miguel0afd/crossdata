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

import org.apache.spark.sql.crossdata.ExecutionType._
import org.apache.spark.sql.crossdata.exceptions.CrossdataException
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CassandraAggregationIT extends CassandraWithSharedContext {

  val nativeErrorMessage =  "The operation cannot be executed without Spark"
  // PRIMARY KEY id
  // CLUSTERING KEY age, comment
  // DEFAULT enrolled
  // SECONDARY_INDEX name

  "The Cassandra connector" should "not execute natively a (SELECT count(*) FROM _)" in {
    assumeEnvironmentIsUpAndRunning

    the[CrossdataException] thrownBy {
      sql(s"SELECT count(*) FROM $Table").collect(Native)
    } should have message nativeErrorMessage

  }

  it should "not execute natively a (SELECT count(*) AS alias FROM _)" in {
    assumeEnvironmentIsUpAndRunning
    the[CrossdataException] thrownBy {
      sql(s"SELECT count(*) as agg FROM $Table").collect(Native)
    } should have message nativeErrorMessage
  }


  it should "not execute natively a (SELECT count(*) FROM _ WHERE _)" in {
    assumeEnvironmentIsUpAndRunning

    the[CrossdataException] thrownBy {
      sql(s"SELECT count(*) FROM $Table WHERE id = 5").collect(Native)
    } should have message nativeErrorMessage

  }

  // NOT SUPPORTED NATIVELY
  it should "not execute natively a (SELECT count(*) FROM _ GROUP BY _)" in {
    assumeEnvironmentIsUpAndRunning

    the[CrossdataException] thrownBy {
      sql(s"SELECT count(*) FROM $Table GROUP BY id").collect(Native)
    } should have message nativeErrorMessage

  }

  // TODO review it in future C* versions (built-in functions will be added)
  it should "not execute natively a (SELECT max(id) FROM _ )" in {
    assumeEnvironmentIsUpAndRunning

    the[CrossdataException] thrownBy {
      sql(s"SELECT max(id) FROM $Table").collect(Native)
    } should have message nativeErrorMessage
  }

}
