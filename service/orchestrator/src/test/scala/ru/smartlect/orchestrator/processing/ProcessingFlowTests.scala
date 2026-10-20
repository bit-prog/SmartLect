package ru.smartlect.orchestrator.processing

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Assertions.{assertEquals, assertNotNull, fail}
import org.junit.jupiter.api.{BeforeEach, Test}
import org.springframework.amqp.core.{AmqpAdmin, MessageBuilder, MessageProperties}
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.{HttpMethod, MediaType}
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.{get, multipart, post}
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.{content, jsonPath, status}
import ru.smartlect.orchestrator.TestAuth
import ru.smartlect.orchestrator.incident.IncidentRepository
import ru.smartlect.orchestrator.lecture.ProcessingStage
import ru.smartlect.orchestrator.lecture.ProcessingStage.*
import ru.smartlect.orchestrator.storage.ObjectStorage

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.compiletime.uninitialized

/**
 * Весь конвейер через настоящий RabbitMQ (нужен docker compose). Тест играет роль воркера:
 * забирает задачи из очередей этапов и шлёт сырые JSON-события, как это делает Python-воркер.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProcessingFlowTests:

  @Autowired private var mvc: MockMvc = uninitialized
  @Autowired private var rabbit: RabbitTemplate = uninitialized
  @Autowired private var admin: AmqpAdmin = uninitialized
  @Autowired private var storage: ObjectStorage = uninitialized
  @Autowired private var incidents: IncidentRepository = uninitialized

  private var auth: String = uninitialized

  @BeforeEach
  def setUp(): Unit =
    ProcessingStage.values.foreach(stage => admin.purgeQueue(Queues.task(stage), false))
    auth = TestAuth.registerUser(mvc)

  @Test
  def runsAllStagesWithOneRetry(): Unit =
    val id = submittedLecture()

    val transcribe = takeTask(TRANSCRIBING, id)
    assertEquals(1, JsonPath.read[Integer](transcribe, "$.attempt"))
    assertEquals(1, JsonPath.read[java.util.List[?]](transcribe, "$.photos").size)

    sendEvent(id, TRANSCRIBING, 1, "PROGRESS", """"progress":40""")
    awaitLecture(id, "PROCESSING", "$.progress", 40)

    storage.putString(JsonPath.read[String](transcribe, "$.transcriptKey"), """{"segments":[]}""", "application/json")
    sendEvent(id, TRANSCRIBING, 1, "COMPLETED", """"durationMs":5400000""")
    takeTask(READING_BOARD, id)
    mvc.perform(get(s"/api/lectures/$id/transcript").header("Authorization", auth))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.segments").isArray)

    // Первая ошибка — этап перезапускается с attempt = 2
    sendEvent(id, READING_BOARD, 1, "FAILED", """"error":"tesseract crashed"""")
    val retry = takeTask(READING_BOARD, id)
    assertEquals(2, JsonPath.read[Integer](retry, "$.attempt"))

    // Запоздавшее событие старой попытки ничего не меняет
    sendEvent(id, READING_BOARD, 1, "COMPLETED", "")
    sendEvent(id, READING_BOARD, 2, "COMPLETED", "")
    takeTask(GENERATING, id)
    sendEvent(id, GENERATING, 1, "COMPLETED", "")
    val assemble = takeTask(ASSEMBLING, id)

    storage.putString(JsonPath.read[String](assemble, "$.notesKey"), "# Матанализ\n\n$$\\int_0^1 x\\,dx$$", "text/markdown")
    sendEvent(id, ASSEMBLING, 1, "COMPLETED", "")
    awaitLecture(id, "DONE", "$.progress", 100)

    mvc.perform(get(s"/api/lectures/$id").header("Authorization", auth))
      .andExpect(jsonPath("$.durationMs").value(5400000))
      .andExpect(jsonPath("$.stage").isEmpty)
      .andExpect(jsonPath("$.completedAt").isNotEmpty)
    mvc.perform(get(s"/api/lectures/$id/notes").header("Authorization", auth))
      .andExpect(status().isOk)
      .andExpect(content().string("# Матанализ\n\n$$\\int_0^1 x\\,dx$$"))

  @Test
  def secondFailureMarksLectureFailedAndCreatesIncident(): Unit =
    val id = submittedLecture()
    takeTask(TRANSCRIBING, id)
    mvc.perform(get(s"/api/lectures/$id/notes").header("Authorization", auth)).andExpect(status().isConflict)

    sendEvent(id, TRANSCRIBING, 1, "FAILED", """"error":"out of memory"""")
    takeTask(TRANSCRIBING, id)
    sendEvent(id, TRANSCRIBING, 2, "FAILED", """"error":"out of memory","details":"Traceback ..."""")
    awaitLecture(id, "FAILED", "$.stage", "TRANSCRIBING")

    val saved = incidents.findByLectureId(UUID.fromString(id))
    assertEquals(1, saved.size)
    assertEquals("out of memory", saved.get(0).errorMessage)
    assertEquals("Traceback ...", saved.get(0).details)

    // Повторная отправка запускает упавший этап заново
    mvc.perform(post(s"/api/lectures/$id/submit").header("Authorization", auth))
      .andExpect(status().isAccepted)
      .andExpect(jsonPath("$.status").value("QUEUED"))
      .andExpect(jsonPath("$.errorMessage").isEmpty)
    assertEquals(1, JsonPath.read[Integer](takeTask(TRANSCRIBING, id), "$.attempt"))

  @Test
  def submitRequiresAudio(): Unit =
    val id = createLecture()
    mvc.perform(post(s"/api/lectures/$id/submit").header("Authorization", auth)).andExpect(status().isConflict)

  private def createLecture(): String =
    val body = mvc.perform(
      post("/api/lectures").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
        .content("""{"title":"Лекция","source":"MOBILE","recordedAt":"2026-10-20T09:00:00Z"}""")
    ).andReturn().getResponse.getContentAsString
    JsonPath.read[String](body, "$.id")

  private def submittedLecture(): String =
    val id = createLecture()
    mvc.perform(
      multipart(HttpMethod.PUT, s"/api/lectures/$id/audio")
        .file(MockMultipartFile("file", "a.m4a", "audio/mp4", Array[Byte](1, 2, 3)))
        .header("Authorization", auth)
    ).andExpect(status().isOk)
    mvc.perform(
      multipart(s"/api/lectures/$id/photos")
        .file(MockMultipartFile("file", "board.jpg", "image/jpeg", Array[Byte](4, 5)))
        .param("offsetMs", "60000").header("Authorization", auth)
    ).andExpect(status().isCreated)
    mvc.perform(post(s"/api/lectures/$id/submit").header("Authorization", auth))
      .andExpect(status().isAccepted)
      .andExpect(jsonPath("$.status").value("QUEUED"))
      .andExpect(jsonPath("$.stage").value("TRANSCRIBING"))
    // Файлы после отправки менять нельзя
    mvc.perform(
      multipart(s"/api/lectures/$id/photos")
        .file(MockMultipartFile("file", "late.jpg", "image/jpeg", Array[Byte](6)))
        .param("offsetMs", "1").header("Authorization", auth)
    ).andExpect(status().isConflict)
    id

  /** Забирает задачу этапа для лекции как сырой JSON. */
  private def takeTask(stage: ProcessingStage, lectureId: String): String =
    val message = rabbit.receive(Queues.task(stage), 10_000)
    assertNotNull(message, s"No task in ${Queues.task(stage)}")
    val json = String(message.getBody, StandardCharsets.UTF_8)
    assertEquals(lectureId, JsonPath.read[String](json, "$.lectureId"))
    assertEquals(stage.toString, JsonPath.read[String](json, "$.stage"))
    json

  /** Шлёт событие без заголовков типа Spring — так же, как Python-воркер. */
  private def sendEvent(lectureId: String, stage: ProcessingStage, attempt: Int, kind: String, extra: String): Unit =
    val fields = Seq(s""""lectureId":"$lectureId"""", s""""stage":"$stage"""", s""""attempt":$attempt""", s""""type":"$kind"""")
      ++ Option(extra).filter(_.nonEmpty)
    val props = MessageProperties()
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON)
    rabbit.send(Queues.Exchange, Queues.Events,
      MessageBuilder.withBody(fields.mkString("{", ",", "}").getBytes(StandardCharsets.UTF_8)).andProperties(props).build())

  private def awaitLecture(id: String, expectedStatus: String, path: String, expected: Any): Unit =
    val deadline = System.currentTimeMillis() + 10_000
    var last = ""
    while System.currentTimeMillis() < deadline do
      last = mvc.perform(get(s"/api/lectures/$id").header("Authorization", auth)).andReturn().getResponse.getContentAsString
      if JsonPath.read[String](last, "$.status") == expectedStatus && JsonPath.read[Any](last, path) == expected then return
      Thread.sleep(100)
    fail(s"Lecture did not reach $expectedStatus with $path = $expected, last: $last")
