package mdoc

import zio.*
import zio.Console.*
import zio.test.*
import zio.test.Assertion.*
import zio.test.environment.*
import java.io.IOException
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.{
  GenericContainer,
  Network
}
import io.getquill._
import mdoc.QuillLocal.AppPostgresContext

object QuillLocal:
  type AppPostgresContext =
    PostgresJdbcContext[
      io.getquill.LowerCase.type
    ]
  def configFromContainer(
      container: PostgresContainer
  ) =
    val pgDataSource =
      new org.postgresql.ds.PGSimpleDataSource()

    val exposedPort =
      container.getMappedPort(5432).nn
    val username = container.getUsername.nn
    val password = container.getPassword.nn
    pgDataSource.setUser(username)
    pgDataSource
      .setPortNumbers(Array(exposedPort))
    pgDataSource.setPassword(password)
    import com.zaxxer.hikari.HikariConfig
    val config = new HikariConfig()
    config.setDataSource(pgDataSource)
    config

  val quillPostgresContext: ZLayer[Has[
    PostgresContainer
  ], Nothing, Has[AppPostgresContext]] =
    ZLayer
      .service[PostgresContainer]
      .map(_.get)
      .flatMap {
        (safePostgres: PostgresContainer) =>
          import com.zaxxer.hikari.HikariDataSource

          val config =
            configFromContainer(safePostgres)
          ZLayer.succeed(
            new PostgresJdbcContext(
              LowerCase,
              new HikariDataSource(config)
            )
          )
      }

  val quillQuery: ZIO[Has[
    AppPostgresContext
  ], Nothing, List[Person]] =
    for
      ctx <- ZIO.service[AppPostgresContext]
    yield
      import ctx._

      val age = 18
      inline def somePeople =
        quote {
          query[Person]
            .filter(p => p.age > lift(age))
        }
      val people: List[Person] = run(somePeople)
      // TODO Get SQL
      people

end QuillLocal

// TODO Move to appropriate file
import org.testcontainers.containers.MockServerContainer
object MockServerClient:
  def citizenInfo(person: Person): ZIO[Has[MockServerContainer], Throwable | String, String] =
    for
      mockServerContainer <-
        ZIO.service[MockServerContainer]
      responseBody <-
        ZIO.attempt {
          import sttp.client3._
          val backend =
            HttpURLConnectionBackend()
          val response =
            basicRequest
              .body("Hello, world!")
              .get(
                uri"http://${mockServerContainer.getHost()}:${mockServerContainer.getServerPort().nn}/person/${person.firstName}"
              )
              .send(backend)

          response
            .body
        }
      responseBodyZ <- ZIO.fromEither(responseBody)
    yield (responseBodyZ)
end MockServerClient