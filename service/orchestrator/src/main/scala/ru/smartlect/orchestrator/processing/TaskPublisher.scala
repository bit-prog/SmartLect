package ru.smartlect.orchestrator.processing

import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import ru.smartlect.orchestrator.lecture.{BoardPhotoRepository, Lecture}
import ru.smartlect.orchestrator.storage.{StorageKeys, StorageProperties}

import scala.jdk.CollectionConverters.*

/** Отправляет воркеру задачу для текущего этапа и попытки лекции. */
@Component
class TaskPublisher(rabbit: RabbitTemplate, photos: BoardPhotoRepository, storage: StorageProperties):

  def publish(lecture: Lecture): Unit =
    val id = lecture.id
    val task = LectureTask(
      lectureId = id,
      stage = lecture.stage,
      attempt = lecture.attempt,
      bucket = storage.bucket,
      audioKey = lecture.audioKey,
      durationMs = Option(lecture.durationMs).map(_.longValue),
      photos = photos.findByLectureIdOrderByOffsetMs(id).asScala.toSeq.map(p => PhotoRef(p.getId, p.objectKey, p.offsetMs)),
      transcriptKey = StorageKeys.transcript(id),
      boardKey = StorageKeys.board(id),
      sectionsKey = StorageKeys.sections(id),
      notesKey = StorageKeys.notes(id)
    )
    rabbit.convertAndSend(Queues.Exchange, Queues.task(lecture.stage), task)
