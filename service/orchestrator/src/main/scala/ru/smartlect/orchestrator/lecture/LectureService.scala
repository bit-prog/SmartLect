package ru.smartlect.orchestrator.lecture

import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.{TransactionSynchronization, TransactionSynchronizationManager}
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import ru.smartlect.orchestrator.storage.{ObjectStorage, StorageKeys}

import java.lang.Long as JLong
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal

@Service
class LectureService(lectures: LectureRepository, photos: BoardPhotoRepository, storage: ObjectStorage):

  private val log = LoggerFactory.getLogger(classOf[LectureService])

  /** Статусы, в которых ещё можно менять файлы лекции (до отправки на обработку). */
  private val Editable = Set(LectureStatus.CREATED, LectureStatus.UPLOADED)

  @Transactional
  def create(ownerId: UUID, request: CreateLectureRequest): LectureResponse =
    // saveAndFlush: createdAt проставляется при INSERT, а он нужен уже в ответе
    val lecture = lectures.saveAndFlush(Lecture(ownerId, request.title.trim, request.source, request.recordedAt))
    LectureResponse.from(lecture, photoCount = 0)

  @Transactional(readOnly = true)
  def list(ownerId: UUID, page: Int, size: Int): PageResponse[LectureResponse] =
    val result = lectures.findByOwnerIdOrderByCreatedAtDesc(ownerId, PageRequest.of(page, size))
    val ids = result.getContent.asScala.map(_.id)
    val counts =
      if ids.isEmpty then Map.empty[UUID, Long]
      else
        photos.countByLectureIds(ids.asJava).asScala
          .map(row => row(0).asInstanceOf[UUID] -> row(1).asInstanceOf[JLong].longValue)
          .toMap
    PageResponse(
      items = result.getContent.asScala.map(l => LectureResponse.from(l, counts.getOrElse(l.id, 0L))).toSeq,
      page = page,
      size = size,
      totalItems = result.getTotalElements,
      totalPages = result.getTotalPages
    )

  @Transactional(readOnly = true)
  def get(ownerId: UUID, lectureId: UUID): LectureResponse =
    val lecture = find(ownerId, lectureId)
    LectureResponse.from(lecture, photos.countByLectureId(lecture.id))

  /** Загружает (или заменяет) аудио лекции. */
  @Transactional
  def uploadAudio(ownerId: UUID, lectureId: UUID, file: MultipartFile, durationMs: Option[Long]): LectureResponse =
    val lecture = findEditable(ownerId, lectureId)
    val contentType = requireContentType(file, "audio/")
    durationMs.filter(_ < 0).foreach(_ => throw badRequest("durationMs must be >= 0"))

    // Ключ фиксированный, поэтому повторная загрузка просто перезаписывает файл
    val key = StorageKeys.audio(lecture.id)
    val in = file.getInputStream
    try storage.put(key, in, file.getSize, contentType)
    finally in.close()

    lecture.audioKey = key
    lecture.audioContentType = contentType
    lecture.audioSizeBytes = file.getSize
    durationMs.foreach(d => lecture.durationMs = d)
    lecture.status = LectureStatus.UPLOADED
    LectureResponse.from(lecture, photos.countByLectureId(lecture.id))

  @Transactional
  def addPhoto(ownerId: UUID, lectureId: UUID, file: MultipartFile, offsetMs: Long): PhotoResponse =
    val lecture = findEditable(ownerId, lectureId)
    val contentType = requireContentType(file, "image/")
    if offsetMs < 0 then throw badRequest("offsetMs must be >= 0")

    val photoId = UUID.randomUUID()
    val key = StorageKeys.photo(lecture.id, photoId)
    val in = file.getInputStream
    try storage.put(key, in, file.getSize, contentType)
    finally in.close()

    try PhotoResponse.from(photos.saveAndFlush(BoardPhoto(photoId, lecture.id, offsetMs, key, contentType, file.getSize)))
    catch
      case NonFatal(e) =>
        deleteQuietly(key)
        throw e

  @Transactional(readOnly = true)
  def listPhotos(ownerId: UUID, lectureId: UUID): Seq[PhotoResponse] =
    val lecture = find(ownerId, lectureId)
    photos.findByLectureIdOrderByOffsetMs(lecture.id).asScala.map(PhotoResponse.from).toSeq

  @Transactional
  def deletePhoto(ownerId: UUID, lectureId: UUID, photoId: UUID): Unit =
    val lecture = findEditable(ownerId, lectureId)
    val photo = photos.findByIdAndLectureId(photoId, lecture.id).toScala
      .getOrElse(throw ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"))
    photos.delete(photo)
    afterCommit(deleteQuietly(photo.objectKey))

  /** Готовый конспект в Markdown. */
  @Transactional(readOnly = true)
  def notes(ownerId: UUID, lectureId: UUID): String =
    val lecture = find(ownerId, lectureId)
    if lecture.status != LectureStatus.DONE then
      throw ResponseStatusException(HttpStatus.CONFLICT, s"Notes are not ready, lecture is ${lecture.status}")
    storage.readString(StorageKeys.notes(lecture.id))
      .getOrElse(throw ResponseStatusException(HttpStatus.NOT_FOUND, "Notes file is missing"))

  /** Расшифровка (JSON с сегментами); появляется после этапа TRANSCRIBING. */
  @Transactional(readOnly = true)
  def transcript(ownerId: UUID, lectureId: UUID): String =
    val lecture = find(ownerId, lectureId)
    storage.readString(StorageKeys.transcript(lecture.id))
      .getOrElse(throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcript is not ready yet"))

  /** Файл фото: поток из S3 и тип содержимого. Поток закрывает вызывающий. */
  @Transactional(readOnly = true)
  def photoContent(ownerId: UUID, lectureId: UUID, photoId: UUID): (java.io.InputStream, String) =
    val lecture = find(ownerId, lectureId)
    val photo = photos.findByIdAndLectureId(photoId, lecture.id).toScala
      .getOrElse(throw ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"))
    val in = storage.open(photo.objectKey)
      .getOrElse(throw ResponseStatusException(HttpStatus.NOT_FOUND, "Photo file is missing"))
    (in, photo.contentType)

  /** Удаляет лекцию; файлы в S3 удаляются после успешного коммита. */
  @Transactional
  def delete(ownerId: UUID, lectureId: UUID): Unit =
    val lecture = find(ownerId, lectureId)
    if lecture.status == LectureStatus.QUEUED || lecture.status == LectureStatus.PROCESSING then
      throw ResponseStatusException(HttpStatus.CONFLICT, "Lecture is being processed")
    // Фото и инциденты удалит ON DELETE CASCADE
    lectures.delete(lecture)
    val prefix = StorageKeys.lecturePrefix(lecture.id)
    afterCommit {
      try storage.deletePrefix(prefix)
      catch case NonFatal(e) => log.warn("Failed to delete S3 objects under {}", prefix, e)
    }

  def find(ownerId: UUID, lectureId: UUID): Lecture =
    // Чужая лекция выглядит как несуществующая: не раскрываем, что такой id есть
    lectures.findByIdAndOwnerId(lectureId, ownerId).toScala
      .getOrElse(throw ResponseStatusException(HttpStatus.NOT_FOUND, "Lecture not found"))

  private def findEditable(ownerId: UUID, lectureId: UUID): Lecture =
    val lecture = find(ownerId, lectureId)
    if !Editable.contains(lecture.status) then
      throw ResponseStatusException(HttpStatus.CONFLICT, s"Lecture files can't be changed in status ${lecture.status}")
    lecture

  private def requireContentType(file: MultipartFile, prefix: String): String =
    if file.isEmpty then throw badRequest("File is empty")
    Option(file.getContentType).filter(_.startsWith(prefix))
      .getOrElse(throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, s"Expected $prefix* file"))

  private def badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

  private def deleteQuietly(key: String): Unit =
    try storage.delete(key)
    catch case NonFatal(e) => log.warn("Failed to delete S3 object {}", key, e)

  private def afterCommit(action: => Unit): Unit =
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization:
      override def afterCommit(): Unit = action
    )
