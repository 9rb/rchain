package coop.rchain.casper.engine

import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import coop.rchain.casper.PrettyPrinter
import coop.rchain.casper.protocol.{ApprovedBlock, BlockMessage}
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.shared.{Log, Time}
import fs2.concurrent.{Queue, SignallingRef}
import fs2.{Pipe, Stream}

import scala.concurrent.duration._

object LastFinalizedStateBlockRequester {
  // Possible request statuses
  trait ReqStatus
  case object Init      extends ReqStatus
  case object Requested extends ReqStatus
  case object Received  extends ReqStatus
  case object Done      extends ReqStatus

  final case class ReceiveInfo(requested: Boolean, latest: Boolean, lastlatest: Boolean)

  /**
    * State to control processing of requests
    */
  final case class ST[Key](private val d: Map[Key, ReqStatus], latest: Set[Key], lowerBound: Long) {
    // Adds new keys to Init state, ready for processing. Existing keys are skipped.
    def add(keys: Set[Key]): ST[Key] =
      this.copy(d ++ keys.filterNot(d.contains).map((_, Init)))

    // Get next keys not already requested or
    //  in case of resend together with Requested.
    // Returns updated state with requested keys.
    def getNext(resend: Boolean): (ST[Key], Seq[Key]) = {
      val requested = d
        .filter {
          case (key, status) =>
            // Select initialized or re-request if resending
            def checkForRequest = status == Init || (resend && status == Requested)
            if (latest.isEmpty) {
              // Latest are downloaded, no additional conditions
              checkForRequest
            } else {
              // Only latest are requested first
              checkForRequest && latest(key)
            }
        }
        .mapValues(_ => Requested)
      this.copy(d ++ requested) -> requested.keysIterator.toSeq
    }

    // Returns flag if key is requested.
    def isRequested(k: Key): Boolean = d.get(k).contains(Requested)

    // Confirm key is Received if it was Requested.
    // Returns updated state with the flags if Requested and last latest received.
    def received(k: Key, height: Long): (ST[Key], ReceiveInfo) = {
      val isReq = isRequested(k)
      if (isReq) {
        // Remove message from the set of latest messages (if exists)
        val newLatest    = latest - k
        val isLatest     = latest != newLatest
        val isLastLatest = isLatest && newLatest.isEmpty
        // Calculate new minimum height if latest message
        //  - we need parents of latest message so it's `-1`
        val newLowerBound = if (isLatest) Math.min(height - 1, lowerBound) else lowerBound
        val newSt         = d + ((k, Received))
        // Set new minimum height and update latest
        (this.copy(newSt, newLatest, newLowerBound), ReceiveInfo(isReq, isLatest, isLastLatest))
      } else {
        (this, ReceiveInfo(requested = false, latest = false, lastlatest = false))
      }
    }

    // Mark key as finished (Done) with optionally set minimum lower bound.
    def done(k: Key): ST[Key] = {
      val newSt = d + ((k, Done))
      this.copy(newSt)
    }

    // Returns flag if all keys are marked as finished (Done).
    def isFinished: Boolean = latest.isEmpty && !d.exists { case (_, v) => v != Done }
  }

  object ST {
    // Create requests state with initial keys.
    def apply[Key](
        initial: Set[Key],
        latest: Set[Key] = Set[Key](),
        lowerBound: Long = 0
    ): ST[Key] =
      ST[Key](d = initial.map((_, Init)).toMap, latest, lowerBound)
  }

  /**
    * Create a stream to receive blocks needed for Last Finalized State.
    *
    * @param approvedBlock Last finalized block
    * @param responseQueue Handler of block messages
    * @param initialMinimumHeight Required minimum block height before latest messages are downloaded
    * @param requestTimeout Time after request will be resent if not received
    * @param requestForBlock Send request for block
    * @param containsBlock Check if block is in the store
    * @param putBlockToStore Add block to the store
    * @param validateBlock Check if received block is valid
    * @return fs2.Stream processing all blocks
    */
  def stream[F[_]: Concurrent: Time: Log](
      approvedBlock: ApprovedBlock,
      responseQueue: Queue[F, BlockMessage],
      initialMinimumHeight: Long,
      requestTimeout: FiniteDuration,
      requestForBlock: BlockHash => F[Unit],
      containsBlock: BlockHash => F[Boolean],
      getBlockFromStore: BlockHash => F[BlockMessage],
      putBlockToStore: (BlockHash, BlockMessage) => F[Unit],
      validateBlock: BlockMessage => F[Boolean]
  ): F[Stream[F, Boolean]] = {

    val block = approvedBlock.candidate.block

    // Active validators as per approved block state
    // - for approved state to be complete it is required to have block from each of them
    val latestMessages = block.justifications.map(_.latestBlockHash).toSet

    val initialHashes = latestMessages + block.blockHash

    def createStream(
        st: SignallingRef[F, ST[BlockHash]],
        requestQueue: Queue[F, Boolean]
    ): Stream[F, Boolean] = {

      def broadcastStreams(ids: Seq[BlockHash]) = {
        // Create broadcast requests to peers
        val broadcastRequests = ids.map(x => Stream.eval(requestForBlock(x)))
        // Create stream of requests
        Stream(broadcastRequests: _*)
      }

      /**
        * Validate received block, check if it was requested and if block hash is correct.
        *  Following justifications from last finalized block gives us proof for all ancestor blocks.
        */
      def validateReceivedBlock(block: BlockMessage) = {
        def invalidBlockMsg =
          s"Received ${PrettyPrinter.buildString(block)} with invalid hash. Ignored block."
        for {
          isRequested      <- st.get.map(_.isRequested(block.blockHash))
          blockHashIsValid <- if (isRequested) validateBlock(block) else false.pure[F]

          // Log invalid block if block is requested but hash is invalid
          _ <- Log[F].warn(invalidBlockMsg).whenA(isRequested && !blockHashIsValid)

          // Try accept received block if it has valid hash
          isReceived <- if (blockHashIsValid) {
                         val blockNumber = ProtoUtil.blockNumber(block)
                         for {
                           // Mark block as received and calculate minimum height (if latest)
                           receivedResult <- st.modify(_.received(block.blockHash, blockNumber))
                           // Result if block is received and if last latest is received
                           ReceiveInfo(isReceived, isReceivedLatest, isLastLatest) = receivedResult

                           // Log minimum height when last latest block is received
                           minimumHeight <- st.get.map(_.lowerBound)
                           _ <- Log[F]
                                 .info(
                                   s"Latest blocks downloaded. Minimum block height is $minimumHeight."
                                 )
                                 .whenA(isLastLatest)

                           // Update dependencies for requesting
                           requestDependencies = Sync[F].delay(
                             ProtoUtil.dependenciesHashesOf(block)
                           ) >>= (deps => st.update(_.add(deps.toSet)))

                           // Accept block if it's requested and satisfy conditions
                           // - received one of latest messages
                           // - requested and block number is greater than minimum
                           blockIsAccepted = isReceivedLatest || isReceived && blockNumber >= minimumHeight
                           _               <- requestDependencies.whenA(blockIsAccepted)
                         } yield isReceived
                       } else false.pure[F]
        } yield isReceived
      }

      def saveBlock(block: BlockMessage) =
        for {
          // Save block to the store
          alreadySaved <- containsBlock(block.blockHash)
          _            <- putBlockToStore(block.blockHash, block).whenA(!alreadySaved)

          // Mark block download as done
          _ <- st.update(_.done(block.blockHash))
        } yield ()

      import cats.instances.list._

      /**
        * Request stream is pulling new block hashes ready for broadcast requests.
        */
      val requestStream = for {
        // Request queue is a trigger when to check the state
        resend <- requestQueue.dequeue

        // Check if stream is finished (no more requests)
        isEnd <- Stream.eval(st.get.map(_.isFinished))

        // Take next set of items to request (w/o duplicates)
        hashes <- Stream.eval(st.modify(_.getNext(resend)))

        // Add block hashes for requesting
        _ <- Stream.eval(st.update(_.add(hashes.toSet)))

        // Check existing blocks
        existingHashes <- Stream.eval(hashes.toList.filterA(containsBlock))

        // Process blocks already in the store
        _ <- Stream.eval {
              for {
                existingBlocks <- existingHashes.traverse(getBlockFromStore)
                _ <- existingBlocks.traverse_ { block =>
                      Log[F].info(s"Process existing ${PrettyPrinter.buildString(block)}") *>
                        responseQueue.enqueue1(block)
                    }
              } yield ()
            }

        // Missing blocks not already in the block store
        missingBlocks = hashes.diff(existingHashes)

        // Send all requests in parallel for missing blocks
        _ <- broadcastStreams(missingBlocks).parJoinUnbounded
              .whenA(!isEnd && missingBlocks.nonEmpty)
      } yield isEnd

      /**
        * Response stream is handling incoming block messages.
        */
      val responseStream = for {
        // Response queue is incoming message source / async callback handler
        block <- responseQueue.dequeue

        // Validate and mark received block
        isValid <- Stream.eval(validateReceivedBlock(block))

        // Save block to DAG store
        _ <- Stream.eval(saveBlock(block)).whenA(isValid)

        // Trigger request queue (without resend of already requested)
        _ <- Stream.eval(requestQueue.enqueue1(false))
      } yield ()

      /**
        * Timeout to resend block requests if response is not received.
        */
      val timeoutMsg = s"No block responses for $requestTimeout. Resending requests."
      val resendStream = Stream.eval(
        // Trigger request queue (resend already requested)
        Time[F].sleep(requestTimeout) *> requestQueue.enqueue1(true) <* Log[F].warn(timeoutMsg)
      )
      // Switch to resend if response is not triggered after timeout
      // - response message reset timeout by canceling previous stream
      def withTimeout: Pipe[F, Boolean, Boolean] =
        in => in concurrently resendStream.interruptWhen(in.map(_ => true)).repeat

      /**
        * Final result! Concurrently pulling requests and handling responses
        *  with resend timeout if response is not received.
        */
      requestStream.takeWhile(!_).broadcastThrough(withTimeout) concurrently responseStream
    }

    for {
      // Requester state, fill with validators for required latest messages
      st <- SignallingRef[F, ST[BlockHash]](
             ST(
               initialHashes,
               latest = initialHashes,
               lowerBound = initialMinimumHeight
             )
           )

      // Block requests queue
      requestQueue <- Queue.unbounded[F, Boolean]

      // Light the fire! / Starts the first request for block
      // - `true` if requested blocks should be re-requested
      _ <- requestQueue.enqueue1(false)

      // Create block receiver stream
    } yield createStream(st, requestQueue)
  }

}
