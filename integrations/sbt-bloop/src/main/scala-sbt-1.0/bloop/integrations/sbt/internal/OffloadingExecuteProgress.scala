package bloop.integrations.sbt.internal

import sbt.{ExecuteProgress, Task, Result, ScopedKey, Keys, ProjectRef, AttributeKey}
import sbt.internal.util.RMap

import bloop.integrations.sbt.BloopKeys
import java.{util => ju}

object OffloadingExecuteProgress extends ExecuteProgress[Task] {
  val registeredBloopCompiles = new ju.IdentityHashMap[ScopedKey[_], Task[_]]()

  /*
  def getBloopCompileTaskReadyFor(scopedKey: ScopedKey[_]): Option[Task[_]] = {
    Option(registeredBloopCompiles.get(ref -> conf))
  }
   */

  case class BloopCompileTaskMetadata(
      ref: ProjectRef,
      config: String,
      origin: ScopedKey[_]
  )

  /*private[this] */
  val dependingOnBloopCompile = new ju.IdentityHashMap[Task[_], Task[_]]()

  def initial(): Unit = {
    dependingOnBloopCompile.clear()
    registeredBloopCompiles.clear()
  }

  def stop(): Unit = ()

  def taskFor(scopedKey: ScopedKey[_]): Option[Task[_]] = {
    val mappedTask = registeredBloopCompiles.get(scopedKey)
    if (mappedTask == null) None
    else Option(dependingOnBloopCompile.get(mappedTask))
  }

  def afterRegistered(
      task: Task[_],
      allDeps: Iterable[Task[_]],
      pendingDeps: Iterable[Task[_]]
  ): Unit = {
    def addBloopCompileDepsFor(value: Task[_]): Unit = {
      allDeps.foreach { pending =>
        //println(s"  -> registering ${pending.hashCode} -> ${value.hashCode}")
        dependingOnBloopCompile.put(pending, value)
      }
    }

    readBloopTask(
      task,
      Keys.taskDefinitionKey
    ) match {
      case Some(metadata) =>
        println(s"after registered -> ${metadataToString(metadata)}")
      case None => ()
    }

    readBloopTaskMetadata(task, Keys.taskDefinitionKey, BloopKeys.bloopCompile.key) match {
      case Some(metadata) =>
        assert(!registeredBloopCompiles.containsKey(metadata.origin))
        //println( s"checking task with taskDefinitionKey = BloooCompile, already seen as dep: ${dependingOnBloopCompile .containsKey(task)}")
        //println(s"bloopCompile task found for ${metadata.ref}:${metadata.config}")
        registeredBloopCompiles.put(metadata.origin, task); ()
      case None => ()
    }

    Option(dependingOnBloopCompile.get(task)) match {
      case Some(parent) => addBloopCompileDepsFor(parent)
      case None if task.info.attributes.isEmpty => ()
      case None =>
        readBloopTaskMetadata(task, Keys.taskDefinitionKey, BloopKeys.bloopCompileEntrypoint) match {
          case Some(metadata) =>
            //println(s"bloopCompileEntrypoint task found for ${metadata.ref}:${metadata.config}")
            addBloopCompileDepsFor(task)
          case None => ()
        }
    }
  }

  private def readBloopTask(
      task: Task[_],
      key: AttributeKey[ScopedKey[_]]
  ): Option[BloopCompileTaskMetadata] = {
    task.info.attributes.get(key).flatMap { scopedKey =>
      scopedKey.scope.project.toOption match {
        case Some(ref: ProjectRef) if scopedKey.key.label.startsWith("bloop") =>
          val config = scopedKey.scope.config.toOption.map(_.name).getOrElse("compile")
          //println(s"read successfully key ${key} from ${task.info}")
          Some(BloopCompileTaskMetadata(ref, config, scopedKey))
        case _ => None
      }
    }
  }

  private def readBloopTaskMetadata(
      task: Task[_],
      key: AttributeKey[ScopedKey[_]],
      targetKey: AttributeKey[_]
  ): Option[BloopCompileTaskMetadata] = {
    task.info.attributes.get(key).flatMap { scopedKey =>
      scopedKey.scope.project.toOption match {
        case Some(ref: ProjectRef) if scopedKey.key.label == targetKey.label =>
          val config = scopedKey.scope.config.toOption.map(_.name).getOrElse("compile")
          //println(s"read successfully key ${key} from ${task.info}")
          Some(BloopCompileTaskMetadata(ref, config, scopedKey))
        case _ => None
      }
    }
  }

  def afterReady(task: Task[_]): Unit = {

    readBloopTask(
      task,
      Keys.taskDefinitionKey
    ) match {
      case Some(metadata) =>
        println(s"after ready -> ${metadataToString(metadata)}")
      case None => ()
    }
  }
  def beforeWork(task: Task[_]): Unit = {

    readBloopTask(
      task,
      Keys.taskDefinitionKey
    ) match {
      case Some(metadata) =>
        println(s"before work-> ${metadataToString(metadata)}")
      case None => ()
    }
  }
  def metadataToString(metadata: BloopCompileTaskMetadata): String = {
    s"${metadata.ref}:${metadata.config}:${metadata.origin.key}"
  }

  def afterWork[A](task: Task[A], result: Either[Task[A], Result[A]]): Unit = {
    result match {
      case Left(generatedTask) if dependingOnBloopCompile.containsKey(task) =>
        Option(dependingOnBloopCompile.get(task)) match {
          case None => ()
          case Some(originTask) =>
            readBloopTask(
              generatedTask,
              Keys.taskDefinitionKey
            ) match {
              case Some(metadata) =>
                println(s"dynamic task -> ${metadataToString(metadata)}")
              case None => ()
            }

            dependingOnBloopCompile.put(generatedTask, originTask); ()
        }
      case _ =>
        readBloopTask(
          task,
          Keys.taskDefinitionKey
        ) match {
          case Some(metadata) =>
            println(s"after work -> ${metadataToString(metadata)}")
          case None => ()
        }
        ()
    }
  }

  def afterCompleted[A](task: Task[A], result: Result[A]): Unit = {
    dependingOnBloopCompile.remove(task); ()
  }

  def afterAllCompleted(results: RMap[Task, Result]): Unit = {
    // TODO: Remove this
  }
}