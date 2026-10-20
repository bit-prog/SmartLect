package ru.smartlect.orchestrator.lecture

/** Жизненный цикл лекции, см. docs/requirements.md §4.4. */
enum LectureStatus extends java.lang.Enum[LectureStatus]:
  case CREATED, UPLOADED, QUEUED, PROCESSING, DONE, FAILED

/** Этапы обработки в порядке выполнения. */
enum ProcessingStage extends java.lang.Enum[ProcessingStage]:
  case TRANSCRIBING, READING_BOARD, GENERATING, ASSEMBLING

/** Откуда пришла запись. */
enum LectureSource extends java.lang.Enum[LectureSource]:
  case MOBILE, BROWSER_EXTENSION, MEETING, OTHER
