package ru.smartlect.orchestrator.incident

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import ru.smartlect.orchestrator.lecture.ProcessingStage

import java.time.Instant
import java.util.UUID
import scala.beans.BeanProperty
import scala.compiletime.uninitialized

/** Повторная ошибка обработки, о которой нужно сообщить команде. */
@Entity
@Table(name = "incidents")
class Incident:
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @BeanProperty var id: UUID = uninitialized

  @BeanProperty var lectureId: UUID = uninitialized

  @Enumerated(EnumType.STRING)
  @BeanProperty var stage: ProcessingStage = uninitialized

  @BeanProperty var attempt: Short = 0
  @BeanProperty var errorMessage: String = uninitialized
  @BeanProperty var details: String = uninitialized

  /** Когда ушло уведомление в Telegram; null — ещё не отправлено. */
  @BeanProperty var notifiedAt: Instant = uninitialized

  @CreationTimestamp
  @BeanProperty var createdAt: Instant = uninitialized
