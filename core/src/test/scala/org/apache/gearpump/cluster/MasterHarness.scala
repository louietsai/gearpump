/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gearpump.cluster

import java.io.File
import java.net.{UnknownHostException, SocketTimeoutException, Socket, InetSocketAddress, ServerSocket, URLClassLoader}
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, Address, Props}
import akka.testkit.TestProbe
import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions, ConfigValueFactory}
import org.apache.commons.io.FileUtils
import org.apache.gearpump.cluster.MasterHarness.MockMaster
import org.apache.gearpump.util.Constants._
import org.apache.gearpump.util.{Constants, LogUtil, ActorUtil, Util}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.sys.process.Process
import scala.util.Try

trait MasterHarness {
  private val LOG = LogUtil.getLogger(getClass)

  private var system: ActorSystem = null
  private var systemAddress: Address = null
  private var host: String = null
  private var port: Int = 0
  val PROCESS_BOOT_TIME = Duration(25, TimeUnit.SECONDS)

  def getActorSystem: ActorSystem = system
  def getHost: String = host
  def getPort: Int = port

  def config : Config

  def startActorSystem(): Unit = {
    val systemConfig = config
    system = ActorSystem(MASTER, systemConfig)
    systemAddress = ActorUtil.getSystemAddress(system)
    host = systemAddress.host.get
    port = systemAddress.port.get
    System.setProperty(s"${Constants.GEARPUMP_CLUSTER_MASTERS}.0", s"$host:$port")
    LOG.info(s"Actor system is started, $host, $port")
  }

  def shutdownActorSystem():Unit = {
    system.shutdown()
    system.awaitTermination
    LOG.info(s"Actor system is stopped, $host, $port")
  }

  def convertTestConf(host : String, port : Int) : File = {
    val test = ConfigFactory.parseResourcesAnySyntax("test.conf",
      ConfigParseOptions.defaults.setAllowMissing(true))

    val newConf = test.withValue(GEARPUMP_CLUSTER_MASTERS,
      ConfigValueFactory.fromAnyRef(Array(s"$host:$port").toList.asJava))

    val confFile = File.createTempFile("main", ".conf")
    val serialized = newConf.root().render()
    FileUtils.write(confFile, serialized)
    confFile
  }

  def createMockMaster() : TestProbe = {
    val masterReceiver = TestProbe()(system)
    val master = system.actorOf(Props(classOf[MockMaster], masterReceiver), MASTER)
    masterReceiver
  }

  def isPortUsed(host : String, port : Int) : Boolean = {

    var isPortUsed = true
    val socket = new Socket()
    try {
      socket.setReuseAddress(true)
      socket.connect(new InetSocketAddress(host, port), 1000)
      socket.isConnected
    } catch {
      case ex: SocketTimeoutException  =>
        isPortUsed = false
      case ex: UnknownHostException =>
        isPortUsed = false
      case ex: Throwable =>
        // for other case, we think the port is listened
        isPortUsed = true
    } finally {
      socket.close()
    }
    isPortUsed
  }

  def getContextClassPath : Array[String] = {
    val contextLoader = Thread.currentThread().getContextClassLoader()

    val urlLoader = if (!contextLoader.isInstanceOf[URLClassLoader]) {
      contextLoader.getParent.asInstanceOf[URLClassLoader]
    } else {
      contextLoader.asInstanceOf[URLClassLoader]
    }

    val urls = urlLoader.getURLs()
    val classPath = urls.map { url =>
      new File(url.getPath()).toString
    }
    classPath
  }

  /**
   * Remove trailing $
   */
  def getMainClassName(mainObj : Any) : String = {
    mainObj.getClass.getName.dropRight(1)
  }

  def getMasterListOption(): Array[String] = {
    Array(s"-D${Constants.GEARPUMP_CLUSTER_MASTERS}.0=$getHost:$getPort",
    s"-D${Constants.GEARPUMP_HOSTNAME}=$getHost")
  }
}

object MasterHarness {
  class MockMaster(receiver: TestProbe) extends Actor {
    def receive: Receive = {
      case msg => {
        receiver.ref forward msg
      }
    }
  }
}
