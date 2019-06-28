/**
  * Copyright (c) 2019 The StreamX Project
  * <p>
  * Licensed to the Apache Software Foundation (ASF) under one
  * or more contributor license agreements. See the NOTICE file
  * distributed with this work for additional information
  * regarding copyright ownership. The ASF licenses this file
  * to you under the Apache License, Version 2.0 (the
  * "License"); you may not use this file except in compliance
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

package com.streamxhub.spark.core.util

import java.security.{AccessController, PrivilegedAction}
import scala.util.{Failure, Success, Try}

object SystemPropertyUtil extends Logger {

  /**
    * Returns {@code true} if and only if the system property with the specified {@code key}
    * exists.
    */
  def contains(key: String): Boolean = get(key) != null

  /**
    * Returns the value of the Java system property with the specified
    * {@code key}, while falling back to {@code null} if the property access fails.
    *
    * @return the property value or { @code null}
    */
  def get(key: String): String = get(key, null)

  def get(key: String, default: String): String = {
    key match {
      case null => throw new NullPointerException("key")
      case empty if empty.isEmpty => throw new IllegalArgumentException("key must not be empty.")
      case other =>
        Try {
          System.getSecurityManager match {
            case null => System.getProperty(other)
            case _ => AccessController.doPrivileged(new PrivilegedAction[String]() {
              override def run: String = System.getProperty(other)
            })
          }
        } match {
          case Success(ok) =>
            ok match {
              case null => default
              case value => value
            }
          case Failure(e) =>
            log.warn(s"Unable to retrieve a system property '$other'; default values will be used, ${e.getMessage}.")
            default
        }
    }
  }

  def getBoolean(key: String, default: Boolean): Boolean = {
    val value = get(key)
    value match {
      case null => default
      case "true" | "yes" | "1" => true
      case "false" | "no" | "0" => false
      case other: String if other.isEmpty => false
      case _ =>
        log.warn(s"Unable to parse the boolean system property '$key':$value - using the default value: $default.")
        default
    }
  }

  def getInt(key: String, default: Int): Int = {
    Try(
      get(key).toString.toInt
    ) match {
      case Success(ok) => ok
      case Failure(_) => default
    }
  }

  def getLong(key: String, default: Long): Long = {
    Try(
      get(key).toString.toLong
    ) match {
      case Success(ok) => ok
      case Failure(_) => default
    }
  }

  /**
    * Sets the value of the Java system property with the specified {@code key}
    */
  def setProperty(key: String, value: String): Any = System.getProperties.setProperty(key, value)

  def getOrElseUpdate(key: String, default: String): String = {
    get(key) match {
      case null =>
        setProperty(key, default)
        default
      case other => other
    }
  }

}