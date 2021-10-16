package testcontainers

import zio.*

import scala.jdk.CollectionConverters.*
import zio.Console.*
import org.testcontainers.containers.Network
import testcontainers.proxy.{
  inconsistentFailuresZ,
  jitter
}
import testcontainers.QuillLocal.AppPostgresContext
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.MockServerContainer
import testcontainers.ServiceDataSets.{
  BackgroundData,
  CareerData
}

case class SuspectProfile(
    name: String,
    criminalHistory: Option[String]
)

val makeAProxiedRequest =
  for
    result <-
      CareerHistoryService
        .citizenInfo("Zeb")
        .tapError(reportTopLevelError)
    _ <- printLine("Result: " + result)
  yield ()

object ProxiedRequestScenario
    extends zio.ZIOAppDefault:
  def run =
    makeAProxiedRequest
      .provideSomeLayer[ZEnv](liveLayer)

  val liveLayer: ZLayer[
    Any,
    Throwable,
    Deps.AppDependencies
  ] =
    ZLayer.wire[Deps.AppDependencies](
      ServiceDataSets.careerDataZ,
//      Clock.live,
      Layers.networkLayer,
      NetworkAwareness.live,
      ToxyProxyContainerZ.construct(),
      CareerHistoryService.live
    )

end ProxiedRequestScenario

object ProxiedRequestScenarioUnit
    extends zio.ZIOAppDefault:

  def run =
    makeAProxiedRequest
      .provideSomeLayer[ZEnv](liveLayer)

  val liveLayer =
    ServiceDataSets.careerDataZ >>>
      CareerHistoryHardcoded.live

def reportTopLevelError(
    failure: Throwable | String
) =
  val errorMsg =
    failure match
      case t: Throwable =>
        t.getCause.nn.getMessage
      case s: String =>
        s
  printLine("Failure: " + failure) *>
    printLine(errorMsg)

object ContainerScenarios:
  val logic =
    for
      people <- QuillLocal.quillQuery
      allCitizenInfo <-
        ZIO.foreach(people)(x =>
          CareerHistoryService
            .citizenInfo(x.firstName)
            .tapError(reportTopLevelError)
            .map((x, _))
        )
      _ <-
        ZIO
          .foreach(allCitizenInfo)(citizenInfo =>
            printLine(
              "Citizen info from webserver: " +
                citizenInfo
            )
          )

      personEventProducer <-
        UseKafka.createProducer("person_event")

//      _ <- ZIO.forkAll(???)
      personEventToLocationStream <-
        UseKafka
          .createForwardedStreamZ(
            topicName = "person_event",
            op =
              record =>
                for
                  location <-
                    LocationService
                      .locationOf(record.key.nn)
                yield record.value.nn +
                  s",Location:$location",
            outputTopicName = "housing_history",
            groupId = "housing"
          )
          .timeout(10.seconds)
          .fork

      criminalHistoryStream <-
        UseKafka
          .createForwardedStreamZ(
            topicName = "person_event",
            op =
              record =>
                for
                  criminalHistory <-
                    BackgroundCheckService
                      .criminalHistoryOf(
                        record.key.nn
                      )
                yield s"${record.value.nn},$criminalHistory",
            outputTopicName = "criminal_history",
            groupId = "criminal"
          )
          .timeout(10.seconds)
          .fork

      consumingPoller2 <-
        UseKafka
          .createSink(
            "housing_history",
            record =>
              val location: String =
                RecordManipulation
                  .getField("Location", record)
              printLine(
                s"Location of ${record.key}: $location"
              ),
            "housing"
          )
          .timeout(10.seconds)
          .fork

      criminalPoller <-
        UseKafka
          .createSink(
            "criminal_history",
            record =>
              val location: String =
                RecordManipulation
                  .getField("Criminal", record)
              printLine(
                s"History of ${record.key}: $location"
              ),
            "criminal"
          )
          .timeout(10.seconds)
          .fork

      _ <- ZIO.sleep(1.second)

      producer <-
        ZIO
          .foreachPar(allCitizenInfo)(
            (citizen, citizenInfo) =>
              personEventProducer.submit(
                citizen.firstName,
                s"${citizen.firstName},${citizenInfo}"
              )
          )
          .timeout(10.seconds)
          .fork

      _ <- producer.join
      _ <- criminalHistoryStream.join
      _ <- personEventToLocationStream.join
      _ <- criminalPoller.join
      _ <- consumingPoller2.join
      finalMessagesProduced <-
        ZIO.reduceAll(
          ZIO.succeed(1),
          List(
            personEventProducer
              .messagesProduced
              .get
          )
        )(_ + _)
    yield people

  val backgroundCheckServer: ZLayer[Has[
    Network
  ] & Has[BackgroundData], Throwable, Has[
    BackgroundCheckService
  ]] = BackgroundCheckService.live

  val topicNames =
    List(
      "person_event",
      "housing_history",
      "criminal_history"
    )

  val layer =
    ZLayer.wire[Deps.RubeDependencies](
      ServiceDataSets.careerDataZ,
      ServiceDataSets.locations,
      ServiceDataSets.backgroundData,
      Layers.networkLayer,
      NetworkAwareness.live,
      PostgresContainer.construct("init.sql"),
      KafkaContainerZ.construct(topicNames),
      ToxyProxyContainerZ.construct(),
      QuillLocal.quillPostgresContext,
      CareerHistoryService.live,
      LocationService.live,
      BackgroundCheckService.live
    )

end ContainerScenarios

object RunScenarios extends zio.ZIOAppDefault:
  def run =
    ContainerScenarios
      .logic
      .provideSomeLayer[ZEnv](
        ContainerScenarios.layer
      )
