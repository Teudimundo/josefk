package io.thinkin.josefk

import java.nio.ByteBuffer
import java.util.Properties

import kafka.admin.AdminClient
import kafka.coordinator.MemberSummary
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.internals.ConsumerProtocol
import org.apache.kafka.clients.consumer.{ConsumerConfig, InvalidOffsetException, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.collection.JavaConverters._
import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

trait OffsetManager {
  def setToBeginningByTopic(topic: String*): Unit

  def setToBeginningByPartition(topicPartitions: TopicPartition*): Unit

  def setToEndByTopic(topics: String*): Unit

  def setToEndByPartition(topicPartitions: TopicPartition*): Unit

  def setTo(partitionsToOffsets: (TopicPartition, Long)*): Unit
}

class UnassignedPartition(details: String)
  extends RuntimeException(details)

object UnassignedPartition {
  def apply(details: String = "") = {
    new UnassignedPartition(details)
  }
}

class DefaultOffsetManager(group: String, bootstrapServers: String) extends OffsetManager {
  val props = new Properties();
  props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
  props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
  props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

  override def setToBeginningByTopic(topics: String*): Unit = {
    setToAux(
      topics.toSet, Set(), (c, f) => {
        println(s"Going to set to beginning: ${f}");
        c.seekToBeginning(f)
      })
  }

  override def setToBeginningByPartition(topicPartitions: TopicPartition*): Unit = {
    setToAux(
      topicPartitions.map(_.topic()).toSet,
      topicPartitions.toSet,
      (c, f) => c.seekToBeginning(topicPartitions))
  }


  override def setToEndByTopic(topics: String*): Unit = {
    setToAux(topics.toSet, Set(), (c, f) => c.seekToEnd(f))
  }

  override def setToEndByPartition(topicPartitions: TopicPartition*): Unit = {
    setToAux(
      topicPartitions.map(_.topic()).toSet,
      topicPartitions.toSet,
      (c, f) => c.seekToEnd(topicPartitions))
  }

  override def setTo(partitionsToOffsets: (TopicPartition, Long)*): Unit = {
    setToAux(
      partitionsToOffsets.map(_._1.topic()).toSet,
      partitionsToOffsets.map(_._1).toSet,
      (c, f) => partitionsToOffsets.foreach { case (tp, o) => c.seek(tp, o) })
  }

  private def setToAux(topics: Set[String],
                       topicPartitions: Set[TopicPartition],
                       seekFun: (KafkaConsumer[Array[Byte], Array[Byte]], Set[TopicPartition]) => Unit): Unit = {
    val consumer = consumerSubscribedOnTopics(topics)
    try {
      val existingTopicsPartitionInfo = consumer.listTopics().filter { case (k, v) => topics.contains(k) }
      val existingTopics = existingTopicsPartitionInfo.map(_._1).toSet
      topics.diff(existingTopics).foreach(t => println(s"Warning: topic '$t' does not exist"))
      val allTopicPartitions = existingTopicsPartitionInfo.flatMap {
        case (t, partitionInfos) =>
          partitionInfos.map(p => new TopicPartition(t, p.partition()))
      }.toSet

      val fullTopicPartitions = allTopicPartitions.flatMap { tp: TopicPartition =>
        Option(consumer.committed(tp)).map(_.offset()) match {
          case None =>
            println(s"Warning: partition ${tp} does not exist")
            None
          case Some(-1) =>
            println(s"Warning: partition ${tp} is empty")
            None
          case d =>
            Some(tp)
        }
      }

      val existingTopicPartitions = topicPartitions.intersect(fullTopicPartitions)
      consumer.subscribe(existingTopics)

      consumer.poll(0)

      val assignedPartitions = consumer.assignment().toSet
      if (assignedPartitions != fullTopicPartitions) {
        throw new UnassignedPartition(
          s"Unable to get assigned to some of the requested topic/partition: ${
            fullTopicPartitions
            .diff(assignedPartitions) ++
              assignedPartitions
              .diff(fullTopicPartitions)
          }")
      }

      seekFun(consumer, fullTopicPartitions)

      val modifiedTopicPartitions = if (topicPartitions.isEmpty) fullTopicPartitions else existingTopicPartitions

      val offsetByTopicPartition =
        modifiedTopicPartitions.map { tp =>
          tp -> Try(consumer.position(tp))
        }.flatMap {
          case (tp, Success(offset)) =>
            Some(tp -> new OffsetAndMetadata(offset))
          case (_, Failure(e: InvalidOffsetException)) => None
          case (_, Failure(e)) => throw e
        }.toMap

      consumer.commitSync(offsetByTopicPartition)

      printGroupOffsets(consumer, fullTopicPartitions)
    } finally {
      consumer.close()
    }
  }

  private def printGroupOffsets(consumer: KafkaConsumer[Array[Byte], Array[Byte]],
                                fullTopicPartitions: Set[TopicPartition])

  = {
    println(s"*** New Offsets for the consumer group [${group}]")
    fullTopicPartitions.map { tp =>
      Option(consumer.committed(tp)) match {
        case Some(offsetAndMetadata) =>
          tp -> offsetAndMetadata.offset
        case None =>
          tp -> -1
      }
    } groupBy {
      _._1.topic()
    } foreach { case (topic, tpwithOffets) =>
      val offsetWithPartitions =
        tpwithOffets.toList.sortBy(_._1.partition()).map { case (tp, offset) =>
          s"${tp.partition()}:$offset"
        } mkString (", ")
      println(s"Topic [${topic}]: $offsetWithPartitions")
    }
  }

  private def consumerSubscribedOnTopics(topics: Set[String]): KafkaConsumer[Array[Byte], Array[Byte]]

  = {
    checkGroupStateEmpty(topics)
    val consumer = new KafkaConsumer[Array[Byte], Array[Byte]](
      props,
      new ByteArrayDeserializer,
      new ByteArrayDeserializer)
    consumer

  }

  private def retrieveAssignment(member: MemberSummary): List[TopicPartition] = {
    val assignment = ConsumerProtocol.deserializeAssignment(ByteBuffer.wrap(member.assignment))
    assignment.partitions.asScala.toList
  }

  private def checkGroupStateEmpty(topics: Set[String]): Unit

  = {
    val a = AdminClient.create(props)
    try {
      val description = a.describeGroup(group)
      if (description.state != "Empty" && description.state != "Dead") {
        val connectedOnSameTopic =
          description.members
          .flatMap(m => retrieveAssignment(m).map(s"${m.clientHost}:${m.clientId}" -> _))
          .filter { case (_, assignment) => topics.contains(assignment.topic) }
          .map{case (client, assignment) => s"(${assignment.topic}::${assignment.partition} -> $client)"}

        if ( !connectedOnSameTopic.nonEmpty ) {
          throw UnassignedPartition(
            s"Group ${group} in state '${
              description
              .state
            }', check whether is connected to any topic clientIds: $connectedOnSameTopic")
        }
      }
    } finally {
      a.close()
    }
  }

}