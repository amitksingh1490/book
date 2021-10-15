package testcontainers

import zio.*

import scala.jdk.CollectionConverters.*
import zio.Console.*
import org.testcontainers.containers.{Network}
import testcontainers.proxy.{
  inconsistentFailuresZ,
  jitter
}
import testcontainers.QuillLocal.AppPostgresContext
import org.testcontainers.containers.KafkaContainer

import org.testcontainers.containers.MockServerContainer

case class SuspectProfile(
    name: String,
    criminalHistory: Option[String]
)

val makeAProxiedRequest =
  for
    result <-
      CareerHistoryService
        .citizenInfo(Person("Zeb", "Zestie", 27))
        .tapError(reportTopLevelError)
    _ <- printLine("Result: " + result)
  yield ()

object ProxiedRequestScenario
    extends zio.ZIOAppDefault:
  def run =
    makeAProxiedRequest
      .provideSomeLayer[ZEnv](liveLayer)

  val careerServer: ZLayer[Has[
    Network
  ] & Has[Clock], Throwable, Has[
    CareerHistoryServiceT
  ]] =
    CareerHistoryService.constructContainered(
      List(
        RequestResponsePair(
          "/Joe",
          "Job:Athlete"
        ),
        RequestResponsePair(
          "/Shtep",
          "Job:Salesman"
        ),
        RequestResponsePair(
          "/Zeb",
          "Job:Mechanic"
        )
      ),
      inconsistentFailuresZ
    )

  val liveLayer: ZLayer[
    Any,
    Throwable,
    Deps.AppDependencies
  ] =
    ZLayer.wire[Deps.AppDependencies](
      Clock.live,
      Layers.networkLayer,
      NetworkAwareness.live,
      ToxyProxyContainerZ.construct(),
      careerServer
    )

end ProxiedRequestScenario

object ProxiedRequestScenarioUnit
    extends zio.ZIOAppDefault:

  def run =
    makeAProxiedRequest
      .provideSomeLayer[ZEnv](liveLayer)

  val careerServer: ZLayer[Any, Nothing, Has[
    CareerHistoryServiceT
  ]] =
    ZLayer.succeed(
      CareerHistoryHardcoded(
        List(
          RequestResponsePair(
            "/Joe",
            "Job:Athlete"
          ),
          RequestResponsePair(
            "/Shtep",
            "Job:Salesman"
          ),
          RequestResponsePair(
            "/Zeb",
            "Job:Mechanic"
          )
        ),
        inconsistentFailuresZ *> jitter
      )
    )
  end careerServer

  val liveLayer: ZLayer[Any, Throwable, Has[
    CareerHistoryServiceT
  ]] = careerServer
end ProxiedRequestScenarioUnit

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
            .citizenInfo(x)
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

      housingHistoryConsumer <-
        UseKafka.createConsumer(
          "housing_history",
          "housing"
        )

      criminalHistoryConsumer <-
        UseKafka.createConsumer(
          "criminal_history",
          "criminal"
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
                  // TODO Get rid of person
                  // lookup
                  // and pass plain String name
                  // to
                  // LocationService
                  person <-
                    ZIO.fromOption(
                      people.find(
                        _.firstName ==
                          record.key.nn
                      )
                    )
                  location <-
                    LocationService
                      .locationOf(person)
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
                  // TODO Get rid of person
                  // lookup
                  // and pass plain String name
                  // to
                  // LocationService
                  person <-
                    ZIO.fromOption(
                      people.find(
                        _.firstName ==
                          record.key.nn
                      )
                    )
                  criminalHistory <-
                    BackgroundCheckService
                      .criminalHistoryOf(person)
                yield s"${record.value.nn},$criminalHistory",
            outputTopicName = "criminal_history",
            groupId = "criminal"
          )
          .timeout(10.seconds)
          .fork

      consumingPoller2 <-
        housingHistoryConsumer
          .pollStream()
          .foreach(recordsConsumed =>
            ZIO
              .foreach(recordsConsumed)(record =>
                val location: String =
                  RecordManipulation
                    .getField("Location", record)
                printLine(
                  s"Location of ${record.key}: $location"
                )
              )
          )
          .timeout(10.seconds)
          .fork

      criminalPoller <-
        criminalHistoryConsumer
          .pollStream()
          .foreach(recordsConsumed =>
            ZIO
              .foreach(recordsConsumed)(record =>
                ZIO.debug(
                  "Criminal History record:" +
                    record.value.nn
                ) *> {
                  val location: String =
                    RecordManipulation.getField(
                      "Criminal",
                      record
                    )
                  printLine(
                    s"History of ${record.key}: $location"
                  )
                }
              )
          )
          .timeout(10.seconds)
          .fork

      _ <- ZIO.sleep(1.second)

      producer <-
        ZIO
          .foreachParN(12)(allCitizenInfo)(
            (citizen, citizenInfo) =>
              personEventProducer.submit(
                citizen.firstName,
                s"${citizen.firstName},${citizenInfo}"
              )
          )
          .timeout(4.seconds)
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

      finalMessagesConsumed <-
        ZIO.reduceAll(
          ZIO.succeed(0),
          List(
            housingHistoryConsumer
              .messagesConsumed
              .get
          )
        )(_ + _)
      _ <-
        printLine(
          "Number of messages produced: " +
            finalMessagesProduced
        )
      _ <-
        printLine(
          "Number of messages consumed: " +
            finalMessagesConsumed
        )
    yield people

  val careerServer: ZLayer[Has[
    Network
  ] & Has[Clock], Throwable, Has[
    CareerHistoryServiceT
  ]] =
    CareerHistoryService.constructContainered(
      List(
        RequestResponsePair(
          "/Joe",
          "Job:Athlete"
        ),
        RequestResponsePair(
          "/Shtep",
          "Job:Salesman"
        ),
        RequestResponsePair(
          "/Zeb",
          "Job:Mechanic"
        )
      )
    )

  val locationServer: ZLayer[Has[
    Network
  ], Throwable, Has[LocationService]] =
    LocationService.construct(
      List(
        RequestResponsePair("/Joe", "USA"),
        RequestResponsePair("/Shtep", "Jordan"),
        RequestResponsePair("/Zeb", "Taiwan")
      )
    )

  val backgroundCheckServer: ZLayer[Has[
    Network
  ], Throwable, Has[BackgroundCheckService]] =
    BackgroundCheckService.construct(
      List(
        RequestResponsePair(
          "/Joe",
          "GoodCitizen"
        ),
        RequestResponsePair(
          "/Shtep",
          "Arson,DomesticViolence"
        ),
        RequestResponsePair(
          "/Zeb",
          "SpeedingTicket"
        )
      )
    )

  val topicNames =
    List(
      "person_event",
      "housing_history",
      "criminal_history"
    )

  val layer =
    ZLayer.wire[Deps.RubeDependencies](
      Clock.live,
      Layers.networkLayer,
      NetworkAwareness.live,
      PostgresContainer.construct("init.sql"),
      KafkaContainerZ.construct(topicNames),
      ToxyProxyContainerZ.construct(),
      QuillLocal.quillPostgresContext,
      careerServer,
      locationServer,
      backgroundCheckServer
    )

end ContainerScenarios

object RunScenarios extends zio.ZIOAppDefault:
  def run =
    ContainerScenarios
      .logic
      .provideSomeLayer[ZEnv](
        ContainerScenarios.layer
      )
