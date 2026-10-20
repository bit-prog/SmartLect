package ru.smartlect.orchestrator.storage

import java.util.UUID

/** Ключи объектов лекции в S3. Формат описан в docs/data-model.md. */
object StorageKeys:
  def lecturePrefix(lectureId: UUID): String = s"lectures/$lectureId/"

  def audio(lectureId: UUID): String = s"${lecturePrefix(lectureId)}audio"
  def photo(lectureId: UUID, photoId: UUID): String = s"${lecturePrefix(lectureId)}photos/$photoId"
  def transcript(lectureId: UUID): String = s"${lecturePrefix(lectureId)}transcript.json"
  def board(lectureId: UUID): String = s"${lecturePrefix(lectureId)}board.json"
  def sections(lectureId: UUID): String = s"${lecturePrefix(lectureId)}sections.json"
  def notes(lectureId: UUID): String = s"${lecturePrefix(lectureId)}notes.md"
