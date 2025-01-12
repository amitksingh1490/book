package resourcemanagement

import zio.Console
import zio.{Ref, ZIO}

object Trivial extends zio.ZIOAppDefault:
  enum ResourceState:
    case Closed,
      Open

  def run =

    def acquire(ref: Ref[ResourceState]) =
      for
        _ <-
          Console.printLine("Opening Resource")
        _ <- ref.set(ResourceState.Open)
      yield "Use Me"

    def release(ref: Ref[ResourceState]) =
      for
        _ <- ZIO.debug("Closing Resource")
        _ <- ref.set(ResourceState.Closed)
      yield ()

    def releaseSymbolic(
        ref: Ref[ResourceState]
    ) =
      ZIO.debug("Closing Resource") *>
        ref.set(ResourceState.Closed)

    // This combines creating a managed resource
    // with using it.
    // In normal life, users just get a managed
    // resource from
    // a library and so they don't have to think
    // about acquire
    // & release logic.
    for
      ref <-
        Ref.make[ResourceState](
          ResourceState.Closed
        )
      managed =
        ZIO.acquireRelease(acquire(ref))(_ =>
          release(ref)
        )

      reusable =
        ZIO.scoped {
          managed.map(ZIO.debug(_))
        } // note: Can't just do (Console.printLine) here
      _ <- reusable
      _ <- reusable
      _ <-
        ZIO.scoped {
          managed.flatMap { s =>
            for
              _ <- ZIO.debug(s)
              _ <- ZIO.debug("Blowing up")
              _ <- ZIO.fail("Arggggg")
            yield ()
          }
        }
    yield ()
    end for
  end run
end Trivial
