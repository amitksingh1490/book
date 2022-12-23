package concurrency

import concurrency.LunchVote.Vote.Yay
import zio.*
import zio.concurrent.*

object LunchVote:

  enum Vote:
    case Yay,
      Nay

  case class Voter(name: String, delay: Duration, response: Vote)

  def run(voters: List[Voter]) =
    for
      resultMap <-
        ConcurrentMap.make[Vote, Int](
          Vote.Yay -> 0,
          Vote.Nay -> 0
        )
      voteProcesses = voters.map(voter =>
            getVoteFrom(
              voter,
              resultMap,
              voters.size
            ).onInterrupt(
              ZIO.debug(s"interrupted $voter")
            )
          )
      result <-
        ZIO.raceAll(
          voteProcesses.head,
          voteProcesses.tail,
        )
    yield result
    end for
  end run

  case object NotConclusive

  def getVoteFrom(
                   person: Voter,
                   results: ConcurrentMap[Vote, Int],
                   voterCount: Int
                 ): ZIO[Any, NotConclusive.type, Vote] =
    for
      _ <- ZIO.sleep(person.delay)
      answer = person.response
      currentTally <-
        results
          .computeIfPresent(
            answer,
            (key, previous) => previous + 1
          )
          .someOrFail(
            IllegalStateException(
              "Vote not found"
            )
          )
          .orDie
      _ <-
        ZIO.when(
          currentTally <= voterCount / 2
        )(ZIO.fail(NotConclusive))
    yield answer

end LunchVote
