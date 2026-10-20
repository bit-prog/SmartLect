package ru.smartlect.orchestrator.processing

import org.springframework.boot.context.properties.ConfigurationProperties

import scala.beans.BeanProperty

/** Настройки обработки: `smartlect.processing.*`. */
@ConfigurationProperties(prefix = "smartlect.processing")
class ProcessingProperties:
  /** Сколько раз запускать этап, прежде чем признать лекцию упавшей (1 = без повторов). */
  @BeanProperty var maxAttempts: Int = 2
