package ru.smartlect.orchestrator.lecture

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.{assertEquals, assertNotNull}
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.data.domain.PageRequest
import ru.smartlect.orchestrator.user.{User, UserRepository}

import java.time.Instant
import java.util.UUID
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*

/** Проверяет маппинг сущностей на реальной схеме из Flyway (нужен docker compose). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LectureRepositoryTests:

  @Autowired private var users: UserRepository = uninitialized
  @Autowired private var lectures: LectureRepository = uninitialized
  @Autowired private var photos: BoardPhotoRepository = uninitialized
  @Autowired private var em: EntityManager = uninitialized

  @Test
  def savesLectureWithPhotos(): Unit =
    val email = s"Test-${UUID.randomUUID()}@Example.com"
    val owner = users.save(User(email, "hash"))
    val lecture = Lecture(owner.id, "Матанализ", LectureSource.MOBILE, Instant.parse("2026-10-20T09:00:00Z"))
    lecture.stage = ProcessingStage.READING_BOARD
    lectures.save(lecture)

    val lateId, earlyId = UUID.randomUUID()
    photos.save(BoardPhoto(lateId, lecture.id, 60_000, s"k/$lateId", "image/jpeg", 10))
    photos.save(BoardPhoto(earlyId, lecture.id, 5_000, s"k/$earlyId", "image/jpeg", 10))
    em.flush()
    em.clear()

    val loaded = lectures.findByIdAndOwnerId(lecture.id, owner.id).orElseThrow()
    assertEquals(LectureStatus.CREATED, loaded.status)
    assertEquals(ProcessingStage.READING_BOARD, loaded.stage)
    assertEquals(LectureSource.MOBILE, loaded.source)
    assertNotNull(loaded.createdAt)
    assertEquals(email.toLowerCase, users.findById(owner.id).orElseThrow().email)

    val offsets = photos.findByLectureIdOrderByOffsetMs(lecture.id).asScala.map(_.offsetMs)
    assertEquals(Seq(5_000L, 60_000L), offsets.toSeq)

    val page = lectures.findByOwnerIdOrderByCreatedAtDesc(owner.id, PageRequest.of(0, 10))
    assertEquals(1, page.getTotalElements)
