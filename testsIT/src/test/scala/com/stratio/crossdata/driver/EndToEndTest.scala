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
package com.stratio.crossdata.driver

import com.stratio.crossdata.server.CrossdataServer
import com.stratio.crossdata.test.BaseXDTest
import org.scalatest.BeforeAndAfterAll

trait EndToEndTest extends BaseXDTest with BeforeAndAfterAll {

  var crossdataServer: Option[CrossdataServer] = None

  def init() = {
    crossdataServer = Some(new CrossdataServer)
    crossdataServer.foreach(_.init(null))
    crossdataServer.foreach(_.start())

  }

  def stop() = {
    crossdataServer.get.xdContext.get.dropAllTables()
    crossdataServer.foreach(_.stop())
    crossdataServer.foreach(_.destroy())
  }


  override protected def beforeAll(): Unit = {
    init()
  }

  override protected def afterAll(): Unit = {
    stop()
  }

  def assumeCrossdataUpAndRunning() = {
    assume(crossdataServer.isDefined, "Crossdata server is not up and running")
  }
}