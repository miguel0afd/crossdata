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

package com.stratio.crossdata.streaming.actors

import akka.actor.{ActorSystem, Props}
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import com.stratio.crossdata.streaming.actors.EphemeralStatusActor._
import com.stratio.crossdata.streaming.test.CommonValues
import org.apache.curator.test.TestingServer
import org.apache.curator.utils.CloseableUtils
import org.apache.spark.sql.crossdata.models.EphemeralExecutionStatus
import org.apache.spark.streaming.{Milliseconds, StreamingContext, StreamingContextState}
import org.apache.spark.{SparkConf, SparkContext}
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

@RunWith(classOf[JUnitRunner])
class EphemeralStatusActorIT(_system: ActorSystem) extends TestKit(_system)
with DefaultTimeout
with ImplicitSender
with WordSpecLike
with BeforeAndAfterAll
with CommonValues
with BeforeAndAfter
with ShouldMatchers {

  def this() = this(ActorSystem("EphemeralStatusActor"))

  val sparkConf = new SparkConf().setMaster("local[2]").setAppName(this.getClass.getSimpleName)
  var sc = SparkContext.getOrCreate(sparkConf)
  var ssc: StreamingContext = _
  var zkTestServer: TestingServer = _
  var zookeeperConnection: String = _

  override def beforeAll: Unit = {
    zkTestServer = new TestingServer()
    zkTestServer.start()
    zookeeperConnection = zkTestServer.getConnectString
  }

  override def afterAll: Unit = {
    CloseableUtils.closeQuietly(zkTestServer)
    zkTestServer.stop()
  }

  before {
    if (ssc == null) {
      ssc = new StreamingContext(sc, Milliseconds(500))
    }
  }

  after {
    if (ssc != null) {
      ssc.stop(stopSparkContext = false, stopGracefully = false)
      ssc.awaitTerminationOrTimeout(1000)
      ssc = null
    }
  }

  "EphemeralStatusActor" should {
    "set up with zookeeper configuration  and StreamingContext without any error" in {
      _system.actorOf(Props(new EphemeralStatusActor(ssc,
        Map("connectionString" -> zookeeperConnection), TableName)))
    }
  }

  "EphemeralStatusActor" must {

    "AddListener the first message" in new CommonValues {

      val ephemeralStatusActor =
        _system.actorOf(Props(new EphemeralStatusActor(ssc,
          Map("connectionString" -> zookeeperConnection), TableName)))

      ephemeralStatusActor ! EphemeralStatusActor.AddListener

      expectMsg(new ListenerResponse(true))
    }

    "AddListener is the two messages" in new CommonValues {

      val ephemeralStatusActor =
        _system.actorOf(Props(new EphemeralStatusActor(ssc,
          Map("connectionString" -> zookeeperConnection), TableName)))

      ephemeralStatusActor ! EphemeralStatusActor.AddListener
      expectMsg(new ListenerResponse(true))

      ephemeralStatusActor ! EphemeralStatusActor.AddListener
      expectNoMsg()
    }

    "GetStatus return the status" in new CommonValues {

      val ephemeralStatusActor =
        _system.actorOf(Props(new EphemeralStatusActor(ssc,
          Map("connectionString" -> zookeeperConnection), TableName)))

      ephemeralStatusActor ! EphemeralStatusActor.GetStatus
      expectMsg(new StatusResponse(EphemeralExecutionStatus.NotStarted))
    }

    "CheckStatus shoud make nothing" in new CommonValues {

      val ephemeralStatusActor =
        _system.actorOf(Props(new EphemeralStatusActor(ssc,
          Map("connectionString" -> zookeeperConnection), TableName)))

      ephemeralStatusActor ! EphemeralStatusActor.CheckStatus

      ssc.getState() should be(StreamingContextState.INITIALIZED)
    }

    "SetStatus shoud change the status" in new CommonValues {

      val ephemeralStatusActor =
        _system.actorOf(Props(new EphemeralStatusActor(ssc,
          Map("connectionString" -> zookeeperConnection), TableName)))

      ephemeralStatusActor ! EphemeralStatusActor.GetStatus
      expectMsg(new StatusResponse(EphemeralExecutionStatus.NotStarted))

      ephemeralStatusActor ! EphemeralStatusActor.SetStatus(EphemeralExecutionStatus.Started)
      expectMsg(new StatusResponse(EphemeralExecutionStatus.Started))

      ephemeralStatusActor ! EphemeralStatusActor.GetStatus
      expectMsg(new StatusResponse(EphemeralExecutionStatus.Started))
    }

    "GetStreamingStatus shoud return the correct streaming status" in new CommonValues {

      val ephemeralStatusActor =
        _system.actorOf(Props(new EphemeralStatusActor(ssc,
          Map("connectionString" -> zookeeperConnection), TableName)))

      ephemeralStatusActor ! EphemeralStatusActor.GetStreamingStatus
      expectMsg(StreamingStatusResponse(StreamingContextState.INITIALIZED))

      val lines = ssc.socketTextStream("127.0.0.1", 9666)
      lines.print()
      ssc.start()

      Thread.sleep(3000)

      ephemeralStatusActor ! EphemeralStatusActor.GetStreamingStatus
      expectMsg(StreamingStatusResponse(StreamingContextState.ACTIVE))
    }

    "CheckStatus shoud make StreamingContext stop when status is Stopping without Listener" in new CommonValues {

      val ephemeralStatusActor =
        _system.actorOf(Props(new EphemeralStatusActor(ssc,
          Map("connectionString" -> zookeeperConnection), TableName)))

      ephemeralStatusActor ! EphemeralStatusActor.SetStatus(EphemeralExecutionStatus.Started)
      expectMsg(new StatusResponse(EphemeralExecutionStatus.Started))
      ssc.getState() should be(StreamingContextState.INITIALIZED)

      ephemeralStatusActor ! EphemeralStatusActor.GetStatus
      expectMsg(new StatusResponse(EphemeralExecutionStatus.Started))

      ephemeralStatusActor ! EphemeralStatusActor.SetStatus(EphemeralExecutionStatus.Stopping)
      expectMsg(new StatusResponse(EphemeralExecutionStatus.Stopping))
      ephemeralStatusActor ! EphemeralStatusActor.CheckStatus
      expectMsg(new StatusResponse(EphemeralExecutionStatus.Stopped))
      ssc.getState() should be(StreamingContextState.STOPPED)
    }

    /*"CheckStatus shoud make StreamingContext stop when status is Stopping with Listener" in new CommonValues {

      val ephemeralStatusActor =
        _system.actorOf(Props(new EphemeralStatusActor(ssc,
          Map("connectionString" -> zookeeperConnection), TableName)))

      ephemeralStatusActor ! EphemeralStatusActor.AddListener
      expectMsg(new ListenerResponse(true))

      ephemeralStatusActor ! EphemeralStatusActor.SetStatus(EphemeralExecutionStatus.Started)
      expectMsg(new StatusResponse(EphemeralExecutionStatus.Started))
      ssc.getState() should be(StreamingContextState.INITIALIZED)

      Thread.sleep(6000)

      val lines = ssc.socketTextStream("127.0.0.1", 9667)
      lines.print()
      ssc.start()

      Thread.sleep(3000)

      ssc.getState() should be(StreamingContextState.ACTIVE)
      ephemeralStatusActor ! EphemeralStatusActor.GetStreamingStatus
      expectMsg(StreamingStatusResponse(StreamingContextState.ACTIVE))

      ephemeralStatusActor ! EphemeralStatusActor.SetStatus(EphemeralExecutionStatus.Stopping)
      expectMsg(new StatusResponse(EphemeralExecutionStatus.Stopping))

      ephemeralStatusActor ! EphemeralStatusActor.CheckStatus
      expectMsg(new StatusResponse(EphemeralExecutionStatus.Stopped))

      Thread.sleep(6000)

      ephemeralStatusActor ! EphemeralStatusActor.GetStatus
      expectMsg(StatusResponse(EphemeralExecutionStatus.Stopped))

      StreamingContext.getActive() should be(None)

      ephemeralStatusActor ! EphemeralStatusActor.GetStreamingStatus
      expectMsg(StreamingStatusResponse(StreamingContextState.STOPPED))
    }*/
  }
}
