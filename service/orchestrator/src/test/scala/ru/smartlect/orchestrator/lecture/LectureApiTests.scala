package ru.smartlect.orchestrator.lecture

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.{HttpMethod, MediaType}
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.{delete, get, multipart, post}
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.{jsonPath, status}
import ru.smartlect.orchestrator.TestAuth
import ru.smartlect.orchestrator.storage.{ObjectStorage, StorageKeys}

import java.util.UUID
import scala.compiletime.uninitialized

/** API лекций через HTTP, с настоящими Postgres и S3 (нужен docker compose). */
@SpringBootTest
@AutoConfigureMockMvc
class LectureApiTests:

  @Autowired private var mvc: MockMvc = uninitialized
  @Autowired private var storage: ObjectStorage = uninitialized

  private val audio = MockMultipartFile("file", "lecture.m4a", "audio/mp4", Array.fill[Byte](2048)(1))

  private def photo(name: String) = MockMultipartFile("file", name, "image/jpeg", Array.fill[Byte](512)(2))

  private def createLecture(auth: String, title: String = "Матанализ, лекция 7"): String =
    val body = mvc.perform(
      post("/api/lectures")
        .header("Authorization", auth)
        .contentType(MediaType.APPLICATION_JSON)
        .content(s"""{"title":"$title","source":"MOBILE","recordedAt":"2026-10-20T09:00:00Z"}""")
    )
      .andExpect(status().isCreated)
      .andExpect(jsonPath("$.status").value("CREATED"))
      .andExpect(jsonPath("$.source").value("MOBILE"))
      .andExpect(jsonPath("$.hasAudio").value(false))
      .andExpect(jsonPath("$.createdAt").isNotEmpty)
      .andReturn().getResponse.getContentAsString
    JsonPath.read[String](body, "$.id")

  private def uploadPhoto(auth: String, lectureId: String, name: String, offsetMs: Long): String =
    val body = mvc.perform(
      multipart(s"/api/lectures/$lectureId/photos").file(photo(name))
        .param("offsetMs", offsetMs.toString)
        .header("Authorization", auth)
    ).andExpect(status().isCreated).andReturn().getResponse.getContentAsString
    JsonPath.read[String](body, "$.id")

  @Test
  def createUploadListAndDelete(): Unit =
    val auth = TestAuth.registerUser(mvc)
    val id = createLecture(auth)

    mvc.perform(
      multipart(HttpMethod.PUT, s"/api/lectures/$id/audio").file(audio)
        .param("durationMs", "5400000")
        .header("Authorization", auth)
    )
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.status").value("UPLOADED"))
      .andExpect(jsonPath("$.hasAudio").value(true))
      .andExpect(jsonPath("$.audioSizeBytes").value(2048))
      .andExpect(jsonPath("$.durationMs").value(5400000))

    // Фото приходят не по порядку, отдаются отсортированными по таймкоду
    val late = uploadPhoto(auth, id, "late.jpg", 120_000)
    val early = uploadPhoto(auth, id, "early.jpg", 30_000)
    mvc.perform(get(s"/api/lectures/$id/photos").header("Authorization", auth))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$[0].id").value(early))
      .andExpect(jsonPath("$[1].id").value(late))

    mvc.perform(delete(s"/api/lectures/$id/photos/$late").header("Authorization", auth))
      .andExpect(status().isNoContent)

    mvc.perform(get("/api/lectures").header("Authorization", auth))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.totalItems").value(1))
      .andExpect(jsonPath("$.items[0].id").value(id))
      .andExpect(jsonPath("$.items[0].photoCount").value(1))

    val lectureId = UUID.fromString(id)
    assertTrue(storage.open(StorageKeys.audio(lectureId)).map(_.close()).isDefined)
    assertEquals(None, storage.open(StorageKeys.photo(lectureId, UUID.fromString(late))))

    // Удаление лекции убирает и строки в БД, и файлы в S3
    mvc.perform(delete(s"/api/lectures/$id").header("Authorization", auth)).andExpect(status().isNoContent)
    mvc.perform(get(s"/api/lectures/$id").header("Authorization", auth)).andExpect(status().isNotFound)
    assertEquals(None, storage.open(StorageKeys.audio(lectureId)))
    assertEquals(None, storage.open(StorageKeys.photo(lectureId, UUID.fromString(early))))

  @Test
  def lecturesOfOtherUsersAreInvisible(): Unit =
    val owner = TestAuth.registerUser(mvc)
    val stranger = TestAuth.registerUser(mvc)
    val id = createLecture(owner)

    mvc.perform(get(s"/api/lectures/$id").header("Authorization", stranger)).andExpect(status().isNotFound)
    mvc.perform(delete(s"/api/lectures/$id").header("Authorization", stranger)).andExpect(status().isNotFound)
    mvc.perform(get("/api/lectures").header("Authorization", stranger))
      .andExpect(jsonPath("$.totalItems").value(0))
    mvc.perform(get(s"/api/lectures/$id")).andExpect(status().isUnauthorized)

  @Test
  def rejectsInvalidInput(): Unit =
    val auth = TestAuth.registerUser(mvc)
    val id = createLecture(auth)

    mvc.perform(
      post("/api/lectures").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
        .content("""{"title":"","source":"MOBILE","recordedAt":"2026-10-20T09:00:00Z"}""")
    ).andExpect(status().isBadRequest)
    mvc.perform(
      post("/api/lectures").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
        .content("""{"title":"Без источника","recordedAt":"2026-10-20T09:00:00Z"}""")
    ).andExpect(status().isBadRequest)

    // Картинка вместо аудио
    mvc.perform(multipart(HttpMethod.PUT, s"/api/lectures/$id/audio").file(photo("x.jpg")).header("Authorization", auth))
      .andExpect(status().isUnsupportedMediaType)
    mvc.perform(
      multipart(s"/api/lectures/$id/photos").file(photo("x.jpg")).param("offsetMs", "-1").header("Authorization", auth)
    ).andExpect(status().isBadRequest)
    mvc.perform(get("/api/lectures").param("size", "1000").header("Authorization", auth))
      .andExpect(status().isBadRequest)
    mvc.perform(get(s"/api/lectures/${UUID.randomUUID()}").header("Authorization", auth))
      .andExpect(status().isNotFound)
