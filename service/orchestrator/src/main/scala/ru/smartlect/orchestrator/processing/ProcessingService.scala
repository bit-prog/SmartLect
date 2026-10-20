package ru.smartlect.orchestrator.processing

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.smartlect.orchestrator.incident.{Incident, IncidentRepository}
import ru.smartlect.orchestrator.lecture.*

import java.time.Clock
import java.util.UUID
import scala.jdk.OptionConverters.*

/**
 * Ведёт лекцию по этапам обработки.
 *
 * Задачу воркеру публикуем внутри транзакции: если RabbitMQ недоступен, изменения откатятся
 * и лекция не застрянет в QUEUED без задачи. Обратный случай (задача ушла, а коммит не удался)
 * безопасен: события по устаревшему этапу/попытке игнорируются.
 */
@Service
class ProcessingService(
    lectureService: LectureService,
    lectures: LectureRepository,
    photos: BoardPhotoRepository,
    incidents: IncidentRepository,
    publisher: TaskPublisher,
    props: ProcessingProperties,
    clock: Clock
):

  private val log = LoggerFactory.getLogger(classOf[ProcessingService])

  /** Отправляет лекцию на обработку. Упавшую лекцию перезапускает с этапа, на котором она упала. */
  @Transactional
  def submit(ownerId: UUID, lectureId: UUID): LectureResponse =
    val lecture = lectureService.find(ownerId, lectureId)
    val stage = lecture.status match
      case LectureStatus.UPLOADED => ProcessingStage.TRANSCRIBING
      case LectureStatus.FAILED   => Option(lecture.stage).getOrElse(ProcessingStage.TRANSCRIBING)
      case LectureStatus.CREATED  => throw ResponseStatusException(HttpStatus.CONFLICT, "Upload audio first")
      case other                  => throw ResponseStatusException(HttpStatus.CONFLICT, s"Lecture is already $other")

    lecture.status = LectureStatus.QUEUED
    lecture.submittedAt = clock.instant()
    lecture.completedAt = null
    lecture.errorMessage = null
    startStage(lecture, stage, attempt = 1)
    LectureResponse.from(lecture, photos.countByLectureId(lecture.id))

  @Transactional
  def handle(event: LectureEvent): Unit =
    lectures.findById(event.lectureId).toScala match
      case None =>
        log.info("Event for deleted lecture {} ignored", event.lectureId)
      case Some(lecture) if !isCurrent(lecture, event) =>
        log.info("Stale event {} {} attempt {} for lecture {} ignored (lecture: {} {} attempt {})",
          event.`type`, event.stage, event.attempt, lecture.id, lecture.status, lecture.stage, lecture.attempt)
      case Some(lecture) =>
        event.`type` match
          case EventType.PROGRESS  => onProgress(lecture, event)
          case EventType.COMPLETED => onCompleted(lecture, event)
          case EventType.FAILED    => onFailed(lecture, event)

  private def isCurrent(lecture: Lecture, event: LectureEvent): Boolean =
    (lecture.status == LectureStatus.QUEUED || lecture.status == LectureStatus.PROCESSING)
      && lecture.stage == event.stage
      && lecture.attempt == event.attempt

  private def onProgress(lecture: Lecture, event: LectureEvent): Unit =
    lecture.status = LectureStatus.PROCESSING
    Option(event.progress).foreach(p => lecture.progress = p.intValue.max(0).min(100).toShort)
    rememberDuration(lecture, event)

  private def onCompleted(lecture: Lecture, event: LectureEvent): Unit =
    rememberDuration(lecture, event)
    ProcessingStage.values.lift(lecture.stage.ordinal + 1) match
      case Some(next) =>
        lecture.status = LectureStatus.PROCESSING
        startStage(lecture, next, attempt = 1)
      case None =>
        lecture.status = LectureStatus.DONE
        lecture.stage = null
        lecture.progress = 100
        lecture.completedAt = clock.instant()
        log.info("Lecture {} processed", lecture.id)

  private def onFailed(lecture: Lecture, event: LectureEvent): Unit =
    val error = Option(event.error).getOrElse("Unknown error")
    if lecture.attempt < props.maxAttempts then
      log.warn("Lecture {} failed at {} (attempt {}), retrying: {}", lecture.id, lecture.stage, lecture.attempt, error)
      startStage(lecture, lecture.stage, attempt = lecture.attempt + 1)
    else
      log.error("Lecture {} failed at {} after {} attempts: {}", lecture.id, lecture.stage, lecture.attempt, error)
      lecture.status = LectureStatus.FAILED
      lecture.errorMessage = s"Processing failed at stage ${lecture.stage}. The team has been notified."
      val incident = new Incident
      incident.lectureId = lecture.id
      incident.stage = lecture.stage
      incident.attempt = lecture.attempt
      incident.errorMessage = error
      incident.details = event.details
      incidents.save(incident)

  private def startStage(lecture: Lecture, stage: ProcessingStage, attempt: Int): Unit =
    lecture.stage = stage
    lecture.attempt = attempt.toShort
    lecture.progress = 0
    // flush, чтобы конфликт версий (@Version) обнаружился до отправки задачи
    lectures.saveAndFlush(lecture)
    publisher.publish(lecture)

  private def rememberDuration(lecture: Lecture, event: LectureEvent): Unit =
    if lecture.durationMs == null then Option(event.durationMs).foreach(d => lecture.durationMs = d)
