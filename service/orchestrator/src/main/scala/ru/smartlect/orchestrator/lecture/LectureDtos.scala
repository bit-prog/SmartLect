package ru.smartlect.orchestrator.lecture

import jakarta.validation.constraints.{NotBlank, NotNull, Size}

import java.time.Instant
import java.util.UUID
import scala.annotation.meta.field

case class CreateLectureRequest(
    @(NotBlank @field) @(Size @field)(max = 255) title: String,
    @(NotNull @field) source: LectureSource,
    @(NotNull @field) recordedAt: Instant
)

case class LectureResponse(
    id: UUID,
    title: String,
    source: LectureSource,
    recordedAt: Instant,
    durationMs: Option[Long],
    status: LectureStatus,
    stage: Option[ProcessingStage],
    progress: Int,
    hasAudio: Boolean,
    audioSizeBytes: Option[Long],
    photoCount: Long,
    errorMessage: Option[String],
    createdAt: Instant,
    submittedAt: Option[Instant],
    completedAt: Option[Instant]
)

object LectureResponse:
  def from(lecture: Lecture, photoCount: Long): LectureResponse =
    LectureResponse(
      id = lecture.id,
      title = lecture.title,
      source = lecture.source,
      recordedAt = lecture.recordedAt,
      durationMs = Option(lecture.durationMs).map(_.longValue),
      status = lecture.status,
      stage = Option(lecture.stage),
      progress = lecture.progress,
      hasAudio = lecture.audioKey != null,
      audioSizeBytes = Option(lecture.audioSizeBytes).map(_.longValue),
      photoCount = photoCount,
      errorMessage = Option(lecture.errorMessage),
      createdAt = lecture.createdAt,
      submittedAt = Option(lecture.submittedAt),
      completedAt = Option(lecture.completedAt)
    )

case class PhotoResponse(id: UUID, offsetMs: Long, contentType: String, sizeBytes: Long, createdAt: Instant)

object PhotoResponse:
  def from(photo: BoardPhoto): PhotoResponse =
    PhotoResponse(photo.getId, photo.offsetMs, photo.contentType, photo.sizeBytes, photo.createdAt)

case class PageResponse[T](items: Seq[T], page: Int, size: Int, totalItems: Long, totalPages: Int)
