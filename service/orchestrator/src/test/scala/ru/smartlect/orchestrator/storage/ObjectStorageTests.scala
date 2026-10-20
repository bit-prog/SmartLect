package ru.smartlect.orchestrator.storage

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

import java.io.ByteArrayInputStream
import java.util.UUID

/** Требует запущенный docker compose (S3 на localhost:9100). */
@SpringBootTest
class ObjectStorageTests:

  @Autowired
  private var storage: ObjectStorage = scala.compiletime.uninitialized

  @Test
  def storesReadsAndDeletesLectureFiles(): Unit =
    val lectureId = UUID.randomUUID()
    val audio = Array.fill[Byte](1024)(7)

    storage.put(StorageKeys.audio(lectureId), new ByteArrayInputStream(audio), audio.length, "audio/mp4")
    storage.putString(StorageKeys.notes(lectureId), "# Лекция\n$E = mc^2$", "text/markdown")

    assertEquals(Some("# Лекция\n$E = mc^2$"), storage.readString(StorageKeys.notes(lectureId)))
    val in = storage.open(StorageKeys.audio(lectureId)).get
    try assertTrue(in.readAllBytes().sameElements(audio))
    finally in.close()

    storage.deletePrefix(StorageKeys.lecturePrefix(lectureId))

    assertEquals(None, storage.readString(StorageKeys.notes(lectureId)))
    assertEquals(None, storage.open(StorageKeys.audio(lectureId)))
