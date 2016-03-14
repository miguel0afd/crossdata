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
package com.stratio.crossdata.driver.config

import java.io.File
import java.util.concurrent.ConcurrentHashMap

import com.typesafe.config.{Config, ConfigFactory, ConfigValue, ConfigValueFactory}
import org.apache.log4j.Logger
import org.apache.spark.Logging

import scala.collection.JavaConversions._

class DriverConf extends Logging {

  import DriverConf._

  val logger = Logger.getLogger(classOf[DriverConf])

  private val userSettings = new ConcurrentHashMap[String, ConfigValue]()

  private[crossdata] lazy val finalSettings: Config =
    userSettings.foldLeft(typesafeConf) { case (prevConfig, keyValue) =>
      prevConfig.withValue(keyValue._1, keyValue._2)
    }

  /**
   * Adds a generic key-value
   * akka => e.g akka.loglevel = "INFO"
   * driverConfig => e.g config.cluster.actor = "my-server-actor"
   */
  def set(key: String, value: ConfigValue): DriverConf = {
    userSettings.put(key, value)
    this
  }

  def setAll(settings: Traversable[(String, ConfigValue)]): DriverConf = {
    settings.foreach { case (k, v) => set(k, v) }
    this
  }

  /**
   * @param hostAndPort e.g 127.0.0.1:13420
   */
  def setClusterContactPoint(hostAndPort: String*): DriverConf = {
    userSettings.put(DriverConfigHosts, ConfigValueFactory.fromIterable(hostAndPort))
    this
  }

  /**
   * @param hostAndPort e.g 127.0.0.1:13420
   */
  def setClusterContactPoint(hostAndPort: java.util.List[String]): DriverConf = {
    userSettings.put(DriverConfigHosts, ConfigValueFactory.fromIterable(hostAndPort))
    this
  }

  def setFlattenTables(flatten: Boolean): DriverConf = {
    userSettings.put(DriverFlattenTables, ConfigValueFactory.fromAnyRef(flatten))
    this
  }

  def setTunnelTimeout(seconds: Int): DriverConf = {
    userSettings.put(AkkaClusterRecepcionistTunnelTimeout, ConfigValueFactory.fromAnyRef(seconds * 1000))
    this
  }

  private[crossdata] def get(key: String): AnyRef = {
    getOption(key).getOrElse(throw new NoSuchElementException(key))
  }

  private[crossdata] def getOption(key: String): Option[AnyRef] = {
    Option(finalSettings.getAnyRef(key))
  }

  private[crossdata] def getClusterContactPoint: List[String] = {
    val hosts = finalSettings.getStringList(DriverConfigHosts).toList
    val clusterName = finalSettings.getString(DriverClusterName)
    hosts map (host => s"akka.tcp://$clusterName@$host$ActorsPath")
  }

  private[crossdata] def getFlattenTables: Boolean =
    finalSettings.getBoolean(DriverFlattenTables)


  private val typesafeConf: Config = {

    val defaultConfig = ConfigFactory.load(DriverConfigDefault).getConfig(ParentConfigName)
    val envConfigFile = Option(System.getProperties.getProperty(DriverConfigFile))
    val configFile = envConfigFile.getOrElse(defaultConfig.getString(DriverConfigFile))
    val configResource = defaultConfig.getString(DriverConfigResource)

    //Get the driver-application.conf properties if exists in resources
    val configWithResource: Config = {
      val resource = DriverConf.getClass.getClassLoader.getResource(DriverConfigResource)
      Option(resource).fold {
        logger.warn("User resource (" + configResource + ") haven't been found")
        val file = new File(configResource)
        if (file.exists()) {
          val userConfig = ConfigFactory.parseFile(file).getConfig(ParentConfigName)
          userConfig.withFallback(defaultConfig)
        } else {
          logger.warn("User file (" + configResource + ") haven't been found in classpath")
          defaultConfig
        }
      } { resTemp =>
        val userConfig = ConfigFactory.parseResources(DriverConfigResource).getConfig(ParentConfigName)
        userConfig.withFallback(defaultConfig)
      }
    }

    //Get the user external driver-application.conf properties if exists
    val finalConfig: Config = {
      if (configFile.isEmpty) {
        configWithResource
      } else {
        val file = new File(configFile)
        if (file.exists()) {
          val userConfig = ConfigFactory.parseFile(file).getConfig(ParentConfigName)
          userConfig.withFallback(configWithResource)
        } else {
          logger.warn("User file (" + configFile + ") haven't been found")
          configWithResource
        }
      }
    }

    // TODO Improve implementation
    // System properties
    val finalConfigWithSystemProperties = ConfigFactory.parseProperties(System.getProperties).withFallback(finalConfig)

    val finalConfigWithEnvVars = {
      if (finalConfigWithSystemProperties.hasPath("config.cluster.servers")) {
        val serverNodes = finalConfigWithSystemProperties.getString("config.cluster.servers")
        defaultConfig.withValue(
          DriverConfigHosts,
          ConfigValueFactory.fromIterable(serverNodes.split(",").toList))
      } else {
        finalConfigWithSystemProperties
      }
    }

    logger.debug(s"Cluster.hosts = ${finalConfigWithEnvVars.getAnyRef(DriverConfigHosts)}")

    ConfigFactory.load(finalConfigWithEnvVars)
  }

}


object DriverConf {
  val ActorsPath = "/user/receptionist"
  val DriverConfigDefault = "driver-reference.conf"
  val ParentConfigName = "crossdata-driver"
  val DriverConfigResource = "external.config.resource"
  val DriverConfigFile = "external.config.filename"
  val DriverConfigHosts = "config.cluster.hosts"
  val DriverFlattenTables = "config.flatten-tables"
  val DriverClusterName = "config.cluster.name"
  val AkkaClusterRecepcionistTunnelTimeout = "akka.contrib.cluster.receptionist.response-tunnel-receive-timeout"
}