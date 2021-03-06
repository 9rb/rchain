package coop.rchain.casper

import cats.effect.Sync
import cats.implicits._
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.dag.BlockDagRepresentation
import coop.rchain.casper.protocol.{BlockMessage, Justification}
import coop.rchain.casper.syntax._
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.metrics.Span
import coop.rchain.models.BlockMetadata
import coop.rchain.models.Validator.Validator
import coop.rchain.shared.Log

final class SynchronyConstraintChecker[F[_]: Sync: BlockStore: Estimator: Log: Span](
    synchronyConstraintThreshold: Double
) {
  private def calculateSeenSendersSince(
      lastProposed: BlockMetadata,
      dag: BlockDagRepresentation[F]
  ): F[Set[Validator]] =
    for {
      latestMessages <- dag.latestMessageHashes
      seenSendersSince = lastProposed.justifications.flatMap {
        case Justification(validator, latestBlockHash) =>
          if (validator != lastProposed.sender && latestMessages(validator) != latestBlockHash) {
            // Since we would have fetched missing justifications initially, it can only mean
            // that we have received at least one new block since then
            Some(validator)
          } else {
            None
          }
      }.toSet
    } yield seenSendersSince

  def check(
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[F],
      approvedBlock: BlockMessage,
      validator: Validator
  ): F[Boolean] =
    dag.latestMessageHash(validator).flatMap {
      case Some(lastProposedBlockHash) =>
        for {
          lastProposedBlockMeta <- dag.lookupUnsafe(lastProposedBlockHash)

          checkConstraint = for {
            // Estimate parent blocks for proposing block
            tipHashes             <- Estimator[F].tips(dag, approvedBlock)
            estimatedParentBlocks <- EstimatorHelper.chooseNonConflicting(tipHashes, dag)

            // Get main parent of the main parent block
            mainParentOpt  = estimatedParentBlocks.headOption
            mainParentMeta <- mainParentOpt.liftTo[F](new Exception(s"Parent blocks not found.}"))

            // Loading the whole block is only needed to get post-state hash
            mainParentBlock     <- BlockStore[F].getUnsafe(mainParentMeta.blockHash)
            mainParentStateHash = ProtoUtil.postStateHash(mainParentBlock)

            // Get bonds map from PoS
            // NOTE: It would be useful to have active validators cached in the block in the same way as bonds.
            activeValidators <- runtimeManager.getActiveValidators(mainParentStateHash)

            // Validators weight map filtered by active validators only.
            validatorWeightMap = mainParentMeta.weightMap.filter {
              case (validator, _) => activeValidators.contains(validator)
            }
            // Guaranteed to be present since last proposed block was present
            seenSenders   <- calculateSeenSendersSince(lastProposedBlockMeta, dag)
            sendersWeight = seenSenders.toList.flatMap(validatorWeightMap.get).sum

            // This method can be called on readonly node or not active validator.
            // So map validator -> stake might not have key associated with the node,
            // that's why we need `getOrElse`
            validatorOwnStake     = validatorWeightMap.getOrElse(validator, 0L)
            otherValidatorsWeight = validatorWeightMap.values.sum - validatorOwnStake

            // If there is no other active validators, do not put any constraint (value = 1)
            synchronyConstraintValue = if (otherValidatorsWeight == 0) 1
            else sendersWeight.toDouble / otherValidatorsWeight

            _ <- Log[F].info(
                  s"Seen ${seenSenders.size} senders with weight $sendersWeight out of total $otherValidatorsWeight " +
                    s"(${synchronyConstraintValue} out of $synchronyConstraintThreshold needed)"
                )
          } yield synchronyConstraintValue >= synchronyConstraintThreshold

          // If validator's latest block is genesis, it's not proposed any block yet and hence allowed to propose once.
          latestBlockIsGenesis = lastProposedBlockMeta.blockNum == 0
          allowedToPropose     <- if (latestBlockIsGenesis) true.pure[F] else checkConstraint
        } yield allowedToPropose
      case None =>
        Sync[F].raiseError[Boolean](
          new IllegalStateException("Validator does not have a latest message")
        )
    }
}

object SynchronyConstraintChecker {
  def apply[F[_]](implicit ev: SynchronyConstraintChecker[F]): SynchronyConstraintChecker[F] =
    ev

  def apply[F[_]: Sync: BlockStore: Estimator: Log: Span](
      synchronyConstraintThreshold: Double
  ): SynchronyConstraintChecker[F] =
    new SynchronyConstraintChecker[F](synchronyConstraintThreshold)
}
