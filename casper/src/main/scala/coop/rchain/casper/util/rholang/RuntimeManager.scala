package coop.rchain.casper.util.rholang

import com.google.protobuf.ByteString
import coop.rchain.catscontrib.TaskContrib._
import coop.rchain.models.Par
import coop.rchain.rholang.interpreter.{Reduce, Runtime}
import coop.rchain.rholang.interpreter.storage.StoragePrinter
import coop.rchain.rspace.{trace, Blake2b256Hash}
import monix.execution.Scheduler

import scala.concurrent.SyncVar
import scala.util.{Failure, Success, Try}
import RuntimeManager.StateHash
import monix.eval.Task

//runtime is a SyncVar for thread-safety, as all checkpoints share the same "hot store"
class RuntimeManager private (runtimeContainer: SyncVar[Runtime]) {

  def replayComputeState(log: trace.Log)(
      implicit scheduler: Scheduler): (StateHash, List[Par]) => Either[Throwable, StateHash] = {
    (hash: StateHash, terms: List[Par]) =>
      {
        val runtime   = runtimeContainer.take()
        val blakeHash = Blake2b256Hash.fromByteArray(hash.toByteArray)
        val riggedRuntime = Try(runtime.replaySpace.rig(blakeHash, log)) match {
          case Success(_) => runtime
          case Failure(ex) =>
            runtimeContainer.put(runtime)
            throw ex
        }
        val error = eval(terms, riggedRuntime.replayReducer)
        val newHash = error.fold[Either[Throwable, ByteString]](Right(
          ByteString.copyFrom(riggedRuntime.space.createCheckpoint().root.bytes.toArray)))(Left(_))
        runtimeContainer.put(riggedRuntime)
        newHash
      }
  }

  def computeState(hash: StateHash, terms: List[Par])(
      implicit scheduler: Scheduler): Either[Throwable, StateHash] = {
    val resetRuntime: Runtime = getResetRuntime(hash)
    val error                 = eval(terms, resetRuntime.reducer)
    val newHash = error.fold[Either[Throwable, StateHash]](
      Right(ByteString.copyFrom(resetRuntime.space.createCheckpoint().root.bytes.toArray)))(Left(_))
    runtimeContainer.put(resetRuntime)
    newHash
  }

  def storageRepr(hash: StateHash): String = {
    val resetRuntime = getResetRuntime(hash)
    val result       = StoragePrinter.prettyPrint(resetRuntime.space.store)
    runtimeContainer.put(resetRuntime)
    result
  }

  private def getResetRuntime(hash: StateHash) = {
    val runtime   = runtimeContainer.take()
    val blakeHash = Blake2b256Hash.fromByteArray(hash.toByteArray)
    Try(runtime.space.reset(blakeHash)) match {
      case Success(_) => runtime
      case Failure(ex) =>
        runtimeContainer.put(runtime)
        throw ex
    }
  }

  private def eval(terms: List[Par], reducer: Reduce[Task])(
      implicit scheduler: Scheduler): Option[Throwable] =
    terms match {
      case term :: rest =>
        Try(reducer.inj(term).unsafeRunSync) match {
          case Success(_)  => eval(rest, reducer)
          case Failure(ex) => Some(ex)
        }
      case Nil => None
    }
}

object RuntimeManager {
  type StateHash = ByteString

  def fromRuntime(runtime: SyncVar[Runtime]): (StateHash, RuntimeManager) = {
    val active = runtime.take()
    val hash   = ByteString.copyFrom(active.space.createCheckpoint().root.bytes.toArray)
    runtime.put(active)

    (hash, new RuntimeManager(runtime))
  }
}
