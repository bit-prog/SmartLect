package ru.smartlect.orchestrator.lecture

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.springframework.data.domain.Persistable

import java.time.Instant
import java.util.UUID
import scala.beans.BeanProperty
import scala.compiletime.uninitialized

/** Фото доски, привязанное к моменту записи (`offsetMs` от начала аудио). */
@Entity
@Table(name = "board_photos")
class BoardPhoto extends Persistable[UUID]:
  /** Id задаётся заранее: из него строится ключ в S3 ещё до сохранения строки. */
  @Id
  private var id: UUID = uninitialized

  @BeanProperty var lectureId: UUID = uninitialized
  @BeanProperty var offsetMs: Long = 0
  @BeanProperty var objectKey: String = uninitialized
  @BeanProperty var contentType: String = uninitialized
  @BeanProperty var sizeBytes: Long = 0

  @CreationTimestamp
  @BeanProperty var createdAt: Instant = uninitialized

  // Id назначается вручную, поэтому новизну записи Spring Data узнаёт отсюда, а не по id == null
  @Transient
  private var persisted = false

  override def getId: UUID = id

  override def isNew: Boolean = !persisted

  @PostPersist @PostLoad
  private def markPersisted(): Unit = persisted = true

object BoardPhoto:
  def apply(id: UUID, lectureId: UUID, offsetMs: Long, objectKey: String, contentType: String, sizeBytes: Long): BoardPhoto =
    val photo = new BoardPhoto
    photo.id = id
    photo.lectureId = lectureId
    photo.offsetMs = offsetMs
    photo.objectKey = objectKey
    photo.contentType = contentType
    photo.sizeBytes = sizeBytes
    photo
