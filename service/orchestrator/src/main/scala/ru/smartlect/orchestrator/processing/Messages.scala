package ru.smartlect.orchestrator.processing

import ru.smartlect.orchestrator.lecture.ProcessingStage

import java.lang.{Integer as JInteger, Long as JLong}
import java.util.UUID

/** Фото доски в задаче воркеру. */
case class PhotoRef(id: UUID, key: String, offsetMs: Long)

/**
 * Задача этапа обработки (оркестратор → воркер). Формат — JSON, его же читает Python-воркер.
 * Каждый этап берёт нужные ему ключи и пишет результат по своему ключу.
 */
case class LectureTask(
    lectureId: UUID,
    stage: ProcessingStage,
    attempt: Int,
    bucket: String,
    audioKey: String,
    durationMs: Option[Long],
    photos: Seq[PhotoRef],
    transcriptKey: String,
    boardKey: String,
    sectionsKey: String,
    notesKey: String
)

enum EventType extends java.lang.Enum[EventType]:
  case PROGRESS, COMPLETED, FAILED

/**
 * Событие от воркера (воркер → оркестратор).
 * Необязательные поля — Java-обёртки (null, если не пришли): Jackson надёжнее
 * десериализует их, чем Option[Int] / Option[Long].
 */
case class LectureEvent(
    lectureId: UUID,
    stage: ProcessingStage,
    attempt: Int,
    `type`: EventType,
    progress: JInteger,
    durationMs: JLong,
    error: String,
    details: String
)
