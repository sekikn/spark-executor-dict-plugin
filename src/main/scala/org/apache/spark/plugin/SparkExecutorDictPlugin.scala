/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.plugin

import java.io.File
import java.util

import scala.util.control.NonFatal

import org.mapdb.{DBMaker, HTreeMap, Serializer}

import org.apache.spark.{SparkContext, SparkFiles}
import org.apache.spark.api.plugin.{DriverPlugin, ExecutorPlugin, PluginContext, SparkPlugin}
import org.apache.spark.internal.Logging
import org.apache.spark.plugin.grpc.DictServer

object DictPluginConf {
  val EXECUTOR_DICT_DB_FILE = "spark.plugins.executorDict.dbFile"
  val EXECUTOR_DICT_PORT = "spark.plugins.executorDict.port"
  val EXECUTOR_DICT_MAP_CACHE_SIZE = "spark.plugins.executorDict.mapCacheSize"
}

object SparkExecutorDictPlugin {
  val DEFAULT_PORT = "6543"
  val MAP_CACHE_SIZE = "10000"
}

class SparkExecutorDictPlugin extends SparkPlugin with Logging {

  override def driverPlugin(): DriverPlugin = {
    new DriverPlugin {
      override def init(sc: SparkContext, ctx: PluginContext): util.Map[String, String] = {
        val dbPath = sc.getConf.get(DictPluginConf.EXECUTOR_DICT_DB_FILE, "")
        val port = sc.getConf.get(DictPluginConf.EXECUTOR_DICT_PORT, "6543")
        val mapCacheSize = sc.getConf.get(DictPluginConf.EXECUTOR_DICT_MAP_CACHE_SIZE, "10000")
        import collection.JavaConverters._
        Map("dbPath" -> dbPath, "port" -> port, "mapCacheSize" -> mapCacheSize).asJava
      }
    }
  }

  private def openAndGetMap(dbPath: String): String => String = try {
    val mapDb = DBMaker.fileDB(dbPath).readOnly().closeOnJvmShutdown().make()
    // Since MapDB `hashMap` uses a tree structure internally, the data reads
    // can be slower than those of a memory-based hash map. So, we should
    // cache frequently-accessed items for fast lookup.
    // - https://jankotek.gitbooks.io/mapdb/content/htreemap/
    val map: HTreeMap[String, String] = mapDb
      .hashMap("map", Serializer.STRING, Serializer.STRING)
      .open()

    (key: String) => map.get(key)
  } catch {
    case NonFatal(_) =>
      throw new RuntimeException(s"Cannot open a specified database: $dbPath")
  }

  private def getDbPathFromSparkFiles(): String = {
    val files = new File(SparkFiles.getRootDirectory())
    val dbFile = files.listFiles.filter(_.getName.endsWith(".db"))
    if (dbFile.length == 0) {
      throw new RuntimeException("No db file found")
    } else if (dbFile.length > 1) {
      throw new RuntimeException(
        s"Multiple db files found: ${dbFile.map(_.getName).mkString(",")}")
    }
    dbFile.head.getAbsolutePath
  }

  override def executorPlugin(): ExecutorPlugin = {
    new ExecutorPlugin() {
      var rpcServ: DictServer = _

      override def init(ctx: PluginContext, extraConf: util.Map[String, String]): Unit = {
        val port = extraConf.get("port")
        val cacheSize = extraConf.get("mapCacheSize")
        val dbPath = {
          val path = extraConf.get("dbPath")
          if (path.isEmpty) {
            getDbPathFromSparkFiles()
          } else {
            path
          }
        }
        logInfo(s"port=$port dbPath=$dbPath mapCacheSize=$cacheSize")
        rpcServ = new DictServer(port.toInt, openAndGetMap(dbPath), cacheSize.toInt)
        super.init(ctx, extraConf)
      }

      override def shutdown(): Unit = {
        rpcServ.shutdown()
      }
    }
  }
}