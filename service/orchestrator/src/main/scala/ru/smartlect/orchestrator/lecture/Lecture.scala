package ru.smartlect.orchestrator.lecture

import jakarta.persistence.*
import org.hibernate.annotations.{CreationTimestamp, UpdateTimestamp}

import java.lang.Long as JLong
import java.time.Instant
import java.util.UUID
import scala.beans.BeanProperty
import scala.compiletime.uninitialized

@Entity
@Table(name = "lectures")
class Lecture:
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @BeanProperty var id: UUID = uninitialized

  @BeanProperty var ownerId: UUID = uninitialized
  @BeanProperty var title: String = uninitialized

  @Enumerated(EnumType.STRING)
  @BeanProperty var source: LectureSource = uninitialized

  @BeanProperty var recordedAt: Instant = uninitialized

  /** Длительность аудио; null, пока неизвестна. */
  @BeanProperty var durationMs: JLong = uninitialized

  @Enumerated(EnumType.STRING)
  @BeanProperty var status: LectureStatus = LectureStatus.CREATED

  /** Текущий этап обработки; null вне обработки. */
  @Enumerated(EnumType.STRING)
  @BeanProperty var stage: ProcessingStage = uninitialized

  @BeanProperty var progress: Short = 0
  @BeanProperty var attempt: Short = 0

  @BeanProperty var audioKey: String = uninitialized
  @BeanProperty var audioContentType: String = uninitialized
  @BeanProperty var audioSizeBytes: JLong = uninitialized

  @BeanProperty var errorMessage: String = uninitialized
  @BeanProperty var submittedAt: Instant = uninitialized
  @BeanProperty var completedAt: Instant = uninitialized

  @Version
  @BeanProperty var version: Long = 0

  @CreationTimestamp
  @BeanProperty var createdAt: Instant = uninitialized

  @UpdateTimestamp
  @BeanProperty var updatedAt: Instant = uninitialized

object Lecture:
  def apply(ownerId: UUID, title: String, source: LectureSource, recordedAt: Instant): Lecture =
    val lecture = new Lecture
    lecture.ownerId = ownerId
    lecture.title = title
    lecture.source = source
    lecture.recordedAt = recordedAt
    lecture
