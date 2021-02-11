package coop.rchain.rspace.history

import cats.implicits._
import cats.effect.Concurrent
import cats.Parallel
import coop.rchain.rspace.state.{RSpaceExporter, RSpaceImporter}
import coop.rchain.rspace.state.instances.{RSpaceExporterStore, RSpaceImporterStore}
import coop.rchain.rspace.{Blake2b256Hash, HistoryReader, HotStoreAction}
import coop.rchain.shared.Serialize
import coop.rchain.store.{KeyValueStore, KeyValueStoreManager}
import org.lmdbjava.EnvFlags
import scodec.Codec

trait HistoryRepository[F[_], C, P, A, K] extends HistoryReader[F, C, P, A, K] {
  def checkpoint(actions: List[HotStoreAction]): F[HistoryRepository[F, C, P, A, K]]

  def reset(root: Blake2b256Hash): F[HistoryRepository[F, C, P, A, K]]

  def history: History[F]

  def exporter: F[RSpaceExporter[F]]

  def importer: F[RSpaceImporter[F]]
}

object HistoryRepositoryInstances {

  def lmdbRepository[F[_]: Concurrent: Parallel, C, P, A, K](
      rootsKeyValueStore: KeyValueStore[F],
      coldKeyValueStore: KeyValueStore[F],
      historyKeyValueStore: KeyValueStore[F]
  )(
      implicit codecC: Codec[C],
      codecP: Codec[P],
      codecA: Codec[A],
      codecK: Codec[K]
  ): F[HistoryRepository[F, C, P, A, K]] =
    for {
      // Roots store
      rootsRepository <- new RootRepository[F](
                          RootsStoreInstances.rootsStore[F](rootsKeyValueStore)
                        ).pure[F]
      currentRoot <- rootsRepository.currentRoot()
      // Cold store
      coldStore = ColdStoreInstances.coldStore[F](coldKeyValueStore)
      // History store
      historyStore = HistoryStoreInstances.historyStore[F](historyKeyValueStore)
      history      = HistoryInstances.merging(currentRoot, historyStore)
      // RSpace importer/exporter / directly operates on Store (lmdb)
      exporter = RSpaceExporterStore[F](historyKeyValueStore, coldKeyValueStore, rootsKeyValueStore)
      importer = RSpaceImporterStore[F](historyKeyValueStore, coldKeyValueStore, rootsKeyValueStore)
    } yield HistoryRepositoryImpl[F, C, P, A, K](
      history,
      rootsRepository,
      coldStore,
      exporter,
      importer
    )
}
