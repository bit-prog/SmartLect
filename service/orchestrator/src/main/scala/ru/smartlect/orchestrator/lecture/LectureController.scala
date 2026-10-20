package ru.smartlect.orchestrator.lecture

import jakarta.validation.Valid
import jakarta.validation.constraints.{Max, Min}
import org.springframework.core.io.InputStreamResource
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import ru.smartlect.orchestrator.auth.CurrentUser
import ru.smartlect.orchestrator.processing.ProcessingService

import java.lang.Long as JLong
import java.util.UUID

// Имена параметров (@PathVariable("id") и т.п.) указаны явно: Scala не сохраняет их в байткоде
@RestController
@RequestMapping(Array("/api/lectures"))
class LectureController(service: LectureService, processing: ProcessingService):

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  def create(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: CreateLectureRequest): LectureResponse =
    service.create(CurrentUser.id(jwt), request)

  @GetMapping
  def list(
      @AuthenticationPrincipal jwt: Jwt,
      @RequestParam(name = "page", defaultValue = "0") @Min(0) page: Int,
      @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) size: Int
  ): PageResponse[LectureResponse] =
    service.list(CurrentUser.id(jwt), page, size)

  @GetMapping(Array("/{id}"))
  def get(@AuthenticationPrincipal jwt: Jwt, @PathVariable("id") id: UUID): LectureResponse =
    service.get(CurrentUser.id(jwt), id)

  @DeleteMapping(Array("/{id}"))
  @ResponseStatus(HttpStatus.NO_CONTENT)
  def delete(@AuthenticationPrincipal jwt: Jwt, @PathVariable("id") id: UUID): Unit =
    service.delete(CurrentUser.id(jwt), id)

  @PutMapping(path = Array("/{id}/audio"), consumes = Array(MediaType.MULTIPART_FORM_DATA_VALUE))
  def uploadAudio(
      @AuthenticationPrincipal jwt: Jwt,
      @PathVariable("id") id: UUID,
      @RequestPart("file") file: MultipartFile,
      @RequestParam(name = "durationMs", required = false) durationMs: JLong
  ): LectureResponse =
    service.uploadAudio(CurrentUser.id(jwt), id, file, Option(durationMs).map(_.longValue))

  @PostMapping(path = Array("/{id}/photos"), consumes = Array(MediaType.MULTIPART_FORM_DATA_VALUE))
  @ResponseStatus(HttpStatus.CREATED)
  def addPhoto(
      @AuthenticationPrincipal jwt: Jwt,
      @PathVariable("id") id: UUID,
      @RequestPart("file") file: MultipartFile,
      @RequestParam("offsetMs") offsetMs: Long
  ): PhotoResponse =
    service.addPhoto(CurrentUser.id(jwt), id, file, offsetMs)

  @GetMapping(Array("/{id}/photos"))
  def listPhotos(@AuthenticationPrincipal jwt: Jwt, @PathVariable("id") id: UUID): Seq[PhotoResponse] =
    service.listPhotos(CurrentUser.id(jwt), id)

  @DeleteMapping(Array("/{id}/photos/{photoId}"))
  @ResponseStatus(HttpStatus.NO_CONTENT)
  def deletePhoto(
      @AuthenticationPrincipal jwt: Jwt,
      @PathVariable("id") id: UUID,
      @PathVariable("photoId") photoId: UUID
  ): Unit =
    service.deletePhoto(CurrentUser.id(jwt), id, photoId)

  @GetMapping(Array("/{id}/photos/{photoId}/content"))
  def photoContent(
      @AuthenticationPrincipal jwt: Jwt,
      @PathVariable("id") id: UUID,
      @PathVariable("photoId") photoId: UUID
  ): ResponseEntity[InputStreamResource] =
    val (in, contentType) = service.photoContent(CurrentUser.id(jwt), id, photoId)
    ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType)).body(InputStreamResource(in))

  /** Отправить на обработку; для упавшей лекции — перезапуск с упавшего этапа. */
  @PostMapping(Array("/{id}/submit"))
  @ResponseStatus(HttpStatus.ACCEPTED)
  def submit(@AuthenticationPrincipal jwt: Jwt, @PathVariable("id") id: UUID): LectureResponse =
    processing.submit(CurrentUser.id(jwt), id)

  @GetMapping(path = Array("/{id}/notes"), produces = Array("text/markdown;charset=UTF-8"))
  def notes(@AuthenticationPrincipal jwt: Jwt, @PathVariable("id") id: UUID): String =
    service.notes(CurrentUser.id(jwt), id)

  @GetMapping(path = Array("/{id}/transcript"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
  def transcript(@AuthenticationPrincipal jwt: Jwt, @PathVariable("id") id: UUID): String =
    service.transcript(CurrentUser.id(jwt), id)
