/*
 * Copyright (c) 2019 The StreamX Project
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.streamxhub.streamx.flink.core

import com.streamxhub.streamx.common.conf.ConfigConst.KEY_FLINK_SQL
import com.streamxhub.streamx.common.enums.SqlErrorType
import com.streamxhub.streamx.common.util.Logger
import com.streamxhub.streamx.flink.core.SqlCommand._
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.configuration.{ConfigOption, Configuration}
import org.apache.flink.table.api.config.{ExecutionConfigOptions, OptimizerConfigOptions, TableConfigOptions}
import org.apache.flink.table.api.{SqlDialect, TableEnvironment}

import java.util
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.{HashMap => JavaHashMap, Map => JavaMap}
import scala.util.{Failure, Success, Try}

object FlinkTableHelper extends Logger {

  private[this] val lock = new ReentrantReadWriteLock().writeLock

  /**
   * all the available sql config options. see
   * https://ci.apache.org/projects/flink/flink-docs-release-1.10/dev/table/config.html
   */
  lazy val tableConfigOptions: JavaMap[String, ConfigOption[_]] = {
    def extractConfig(clazz: Class[_]): JavaMap[String, ConfigOption[_]] = {
      val configOptions = new JavaHashMap[String, ConfigOption[_]]
      clazz.getDeclaredFields.foreach(field => {
        if (field.getType.isAssignableFrom(classOf[ConfigOption[_]])) {
          Try {
            val configOption = field.get(classOf[ConfigOption[_]]).asInstanceOf[ConfigOption[_]]
            configOptions.put(configOption.key, configOption)
          } match {
            case Success(_) =>
            case Failure(e) => logError("Fail to get ConfigOption", e)
          }
        }
      })
      configOptions
    }

    val configOptions = new JavaHashMap[String, ConfigOption[_]]
    val configList = List(
      //classOf[PythonOptions],
      classOf[ExecutionConfigOptions],
      classOf[OptimizerConfigOptions],
      classOf[TableConfigOptions]
    )
    configList.foreach(x => configOptions.putAll(extractConfig(x)))
    configOptions
  }

  private[streamx] def callSql(sql: String, parameter: ParameterTool, context: TableEnvironment)(implicit callbackFunc: Unit => String = null): Unit = {
    val flinkSql: String = if (sql == null || sql.isEmpty) parameter.get(KEY_FLINK_SQL()) else parameter.get(sql)
    val sqlEmptyError = SqlError(SqlErrorType.VERIFY_FAILED, "sql is empty", sql).toString
    require(flinkSql != null && flinkSql.trim.nonEmpty, sqlEmptyError)

    def callback(r: String): Unit = {
      callbackFunc match {
        case null => println(r)
        case x => x(r)
      }
    }

    //TODO registerHiveCatalog
    SqlCommandParser.parseSQL(flinkSql).foreach(x => {
      val args = x.operands.head
      x.command match {
        case USE =>
          context.useDatabase(args)
          logInfo(s"${x.command.name}: $args")
        case USE_CATALOG =>
          context.useCatalog(args)
          logInfo(s"${x.command.name}: $args")
        case SHOW_CATALOGS =>
          val catalogs = context.listCatalogs
          callback(s"%show catalog\n${catalogs.mkString("\n")}")
        case SHOW_CURRENT_CATALOG =>
          val catalog = context.getCurrentCatalog
          callback(s"%show current catalog\n$catalog")
        case SHOW_DATABASES =>
          val databases = context.listDatabases
          callback(s"%show databases\n${databases.mkString("\n")}")
        case SHOW_CURRENT_DATABASE =>
          val database = context.getCurrentDatabase
          callback(s"%show current database\n$database")
        case SHOW_TABLES =>
          val tables = context.listTables().filter(!_.startsWith("UnnamedTable"))
          callback(s"%show tables\n${tables.mkString("\n")}")
        case SHOW_FUNCTIONS =>
          val functions = context.listUserDefinedFunctions()
          callback(s"%table function\n${functions.mkString("\n")}")
        case SHOW_MODULES =>
          val modules = context.listModules()
          callback(s"%show modules\n${modules.mkString("\n")}")
        case SET =>
          if (!tableConfigOptions.containsKey(args)) {
            throw new IllegalArgumentException(s"$args is not a valid table/sql config, please check link: https://ci.apache.org/projects/flink/flink-docs-release-1.10/dev/table/config.html")
          }
          if (TableConfigOptions.TABLE_SQL_DIALECT.key().equalsIgnoreCase(args)) {
            val dialect = x.operands(1)
            if (SqlDialect.HIVE.name().equalsIgnoreCase(dialect)) {
              context.getConfig.setSqlDialect(SqlDialect.HIVE)
              if (SqlDialect.DEFAULT.name().equalsIgnoreCase(dialect)) {
                context.getConfig.setSqlDialect(SqlDialect.DEFAULT)
              }
            }
          } else {
            context.getConfig.getConfiguration.setString(args, x.operands(1))
          }
          logInfo(s"${x.command.name}: $args --> ${x.operands(1)}")
        case RESET =>
          val confDataField = classOf[Configuration].getDeclaredField("confData")
          confDataField.setAccessible(true)
          val confData = confDataField.get(context.getConfig.getConfiguration).asInstanceOf[util.HashMap[String, AnyRef]]
          if (args.toUpperCase == "ALL") {
            confData.synchronized {
              confData.clear()
            }
          } else {
            confData.synchronized {
              confData.remove(args)
            }
          }
          logInfo(s"${x.command.name}: $args")
        case DESC | DESCRIBE =>
          val schema = context.scan(args).getSchema
          val builder = new StringBuilder()
          builder.append("Column\tType\n")
          for (i <- 0 to schema.getFieldCount) {
            builder.append(schema.getFieldName(i).get() + "\t" + schema.getFieldDataType(i).get() + "\n")
          }
          callback(builder.toString())
        case EXPLAIN =>
          val tableResult = context.executeSql(sql)
          val r = tableResult.collect().next().getField(0).toString
          callback(r)
        case INSERT_INTO | INSERT_OVERWRITE |
             CREATE_FUNCTION | DROP_FUNCTION | ALTER_FUNCTION |
             CREATE_CATALOG | DROP_CATALOG |
             CREATE_TABLE | DROP_TABLE | ALTER_TABLE |
             CREATE_VIEW | DROP_VIEW |
             CREATE_DATABASE | DROP_DATABASE | ALTER_DATABASE =>
          try {
            lock.lock()
            context.executeSql(args)
            logInfo(s"${x.command.name}:$args")
          } finally {
            if (lock.isHeldByCurrentThread) {
              lock.unlock()
            }
          }
        case SELECT =>
          // TODO SELECT
          throw new UnsupportedOperationException(s"[StreamX] Unsupported select operation:$sql")
        case _ => throw new Exception(s"[StreamX] Unsupported command: ${x.command}")
      }
    })

    logInfo(s"\n\n\n==============flinkSql==============\n\n $flinkSql\n\n============================\n\n\n")
  }
}
