package streams

import zio.*
import zio.stream.*

case class Order()

/** Possible stages to demo:
  *   1. Ship individual orders as they come 2.
  *      Queue up multiple items and then send 3.
  *      Ship partially-filled truck if it has
  *      been waiting too long
  */
object DeliveryCenter extends ZIOAppDefault:
  sealed trait Truck

  case class TruckInUse(
      queued: List[Order],
      fuse: Promise[Nothing, Unit],
      capacity: Int = 3
  ) extends Truck:
    val isFull: Boolean =
      queued.length == capacity

    val waitingTooLong =
      fuse.isDone.map(done => !done)

  def handle(
      order: Order,
      staged: Ref[Option[TruckInUse]]
  ) =
    def shipIt(reason: String) =
      ZIO.debug(reason + " Ship the orders!") *>
        staged
          .get
          .flatMap(_.get.fuse.succeed(())) *>
        // TODO Should complete latch here before
        // clearing out value
        staged.set(None)

    val loadTruck =
      for
        latch <- Promise.make[Nothing, Unit]
        truck <-
          staged
            .updateAndGet(truck =>
              truck match
                case Some(t) =>
                  Some(
                    t.copy(queued =
                      t.queued :+ order
                    )
                  )
                case None =>
                  Some(
                    TruckInUse(
                      List(order),
                      latch
                    )
                  )
            )
            .map(_.get)
        _ <-
          ZIO.debug(
            "Loading order: " +
              truck.queued.length + "/" +
              truck.capacity
          )
      yield truck

    def shipIfWaitingTooLong(truck: TruckInUse) =
      ZIO
        .whenZIO(truck.waitingTooLong)(
          shipIt(reason =
            "Truck has bit sitting half-full too long."
          )
        )
        .delay(4.seconds)

    for
      truck <- loadTruck
      _ <-
        if (truck.isFull)
          shipIt(reason = "Truck is full.")
        else
          ZIO
            .when(truck.queued.length == 1)(
              ZIO.debug(
                "Adding timeout daemon"
              ) *> shipIfWaitingTooLong(truck)
            )
            .forkDaemon
    yield ()
  end handle

  def run =
    for
      stagedItems <-
        Ref.make[Option[TruckInUse]](None)
      orderStream =
        ZStream.repeatWithSchedule(
          Order(),
          Schedule
            .exponential(1.second, factor = 1.8)
        )
      _ <-
        orderStream
          .foreach(handle(_, stagedItems))
          .timeout(12.seconds)
    yield ()
end DeliveryCenter
