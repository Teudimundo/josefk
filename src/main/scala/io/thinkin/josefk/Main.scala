package io.thinkin.josefk

import org.apache.kafka.common.TopicPartition

import scala.util.{Failure, Success, Try}

sealed trait Mode

case object Beginning extends Mode

case object End extends Mode

case object Mapping extends Mode

object Modes {
  def validate(s: String): Boolean = from(s).isDefined

  def from(s: String): Option[Mode] = s.toLowerCase match {
    case "s" | "start" | "smallest" | "b" | "beginning" =>
      Some(Beginning)
    case "l" | "e" | "largest" | "end" =>
      Some(End)
    case "m" | "mapping" =>
      Some(Mapping)
    case _ =>
      None
  }
}

case class Config(
                   bootstrapServer: String = null,
                   consumerGroup: String = null,
                   mappings: Seq[String] = Seq(),
                   mode: Mode = Beginning
                 )

object Main {

  implicit val modeRead: scopt.Read[Mode] =
    scopt.Read.reads(s => Modes.from(s).getOrElse(throw new IllegalArgumentException(s"Invalid mode: ${s}")))

  val parser = new scopt.OptionParser[Config]("josefk") {
    head("josefk", "0.(0)1")

    opt[String]('s', "boostrapServer").maxOccurs(1).required().action(
      (x, c) =>
        c.copy(bootstrapServer = x)).text("Kafka bootstrap server <host>:<port>")


    opt[Mode]('m', "mode").maxOccurs(1).optional()
    .action(
      (x, c) =>
        c.copy(mode = x)).text("Offset reset mode: 'beginning'(default), 'end', 'mapping'")

    arg[String]("<group>").required().maxOccurs(1).action((x, c) => c.copy(consumerGroup = x))
    arg[String]("<mapping>...")
    .text("each entry has one of the forms: <topic> (beginning/end modes), <topic:partition:offset> (only mapping mode)")
    .required()
    .unbounded()
    .action((x, c) => c.copy(mappings = c.mappings :+ x))
  }

  def main(args: Array[String]): Unit = {
    parser.parse(args, Config()) match {
      case Some(config) => setOffsets(config)
      case None =>
    }
  }

  def setOffsets(config:Config): Unit = {
    val m = new DefaultOffsetManager(config.consumerGroup, config.bootstrapServer)
    config.mode match {
      case Beginning =>
        m.setToBeginningByTopic(config.mappings:_*)
      case End =>
        m.setToEndByTopic(config.mappings:_*)
      case Mapping =>
        val parsedMappings:Seq[(TopicPartition, Long)] = config.mappings.map { mapping =>
          val mappingValues = mapping.split(":")
          if (mappingValues.length != 3)
            throw new IllegalArgumentException(
              s"The string '${mapping}' is not a valid mapping, expected form: '<topic>:<partition>:<offset>'")
          (for {
            partition:Int <- Try(mappingValues(1).toInt)
            offset:Long <- Try(mappingValues(2).toLong)
          } yield new TopicPartition(mappingValues(0), partition) -> offset) match {
            case Success(v) => v
            case Failure(e) => throw new IllegalArgumentException(
              s"In the string '${mapping}' either partition or offset is not a parsable integer value")
          }
        }
        m.setTo(parsedMappings:_*)
    }
  }
}
