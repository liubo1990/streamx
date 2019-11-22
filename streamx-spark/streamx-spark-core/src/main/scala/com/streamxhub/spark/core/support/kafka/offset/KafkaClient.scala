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

package com.streamxhub.spark.core.support.kafka.offset

/**
  *
  *
  * 封装 Kafka
  */

import java.lang.reflect.Constructor
import java.{util => ju}

import com.streamxhub.spark.core.util.Logger
import com.streamxhub.spark.monitor.api.util.PropertiesUtil
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka010._

import scala.reflect.ClassTag

private[kafka] class KafkaClient(val sparkConf: SparkConf) extends Logger with Serializable {

  // 自定义
  private lazy val offsets = {
    sparkConf.get("spark.source.kafka.offset.store.class", "none").trim match {
      case "none" =>
        sparkConf.get("spark.source.kafka.offset.store.type", "none").trim.toLowerCase match {
          case "redis" => new RedisOffset(sparkConf)
          case "hbase" => new HBaseOffset(sparkConf)
          case "kafka" => new DefaultOffset(sparkConf)
          case "none" => new DefaultOffset(sparkConf)
        }
      case clazz =>
        logInfo(s"Custom offset management class $clazz")
        val constructors = {
          val offsetsManagerClass = PropertiesUtil.classForName(clazz)
          offsetsManagerClass
            .getConstructors
            .asInstanceOf[Array[Constructor[_ <: SparkConf]]]
        }
        val constructorTakingSparkConf = constructors.find { c =>
          c.getParameterTypes.sameElements(Array(classOf[SparkConf]))
        }
        constructorTakingSparkConf.get.newInstance(sparkConf).asInstanceOf[Offsets]
    }
  }

  def offsetStoreType: String = offsets.storeType

  /**
    *
    * @param ssc
    * @param kafkaParams
    * @param topics
    * @tparam K
    * @tparam V
    * @return
    */
  def createDirectStream[K: ClassTag, V: ClassTag](ssc: StreamingContext,
                                                   kafkaParams: Map[String, Object],
                                                   topics: Set[String]
                                                  ): InputDStream[ConsumerRecord[K, V]] = {

    var consumerOffsets = Map.empty[TopicPartition, Long]

    kafkaParams.get("group.id") match {
      case Some(groupId) =>
        logInfo(s"createDirectStream witch group.id $groupId topics ${topics.mkString(",")}")
        consumerOffsets = offsets.get(groupId.toString, topics)
      case _ =>
        logInfo(s"createDirectStream witchOut group.id topics ${topics.mkString(",")}")
    }

    if (consumerOffsets.nonEmpty) {
      logInfo(s"read topics ==[$topics]== from offsets ==[$consumerOffsets]==")
      KafkaUtils.createDirectStream[K, V](ssc,
        LocationStrategies.PreferConsistent,
        ConsumerStrategies.Assign[K, V](consumerOffsets.keys, kafkaParams, consumerOffsets)
      )
    } else {
      KafkaUtils.createDirectStream[K, V](ssc,
        LocationStrategies.PreferConsistent,
        ConsumerStrategies.Subscribe[K, V](topics, kafkaParams)
      )
    }

  }

  /**
    *
    * @param sc
    * @param kafkaParams
    * @param offsetRanges
    * @param locationStrategy
    * @tparam K
    * @tparam V
    * @return
    */
  def createRDD[K: ClassTag, V: ClassTag](sc: SparkContext,
                                          kafkaParams: ju.Map[String, Object],
                                          offsetRanges: Array[OffsetRange],
                                          locationStrategy: LocationStrategy): RDD[ConsumerRecord[K, V]] = {
    KafkaUtils.createRDD(sc, kafkaParams, offsetRanges, locationStrategy)
  }


  /**
    * 更新 Offsets
    *
    * @param groupId
    * @param offsetRange
    */
  def updateOffsets(groupId: String, offsetRange: Array[OffsetRange]): Unit = {
    val offsetInfos = offsetRange.map(x => new TopicPartition(x.topic, x.partition) -> x.untilOffset).toMap
    offsets.update(groupId, offsetInfos)
  }

}

