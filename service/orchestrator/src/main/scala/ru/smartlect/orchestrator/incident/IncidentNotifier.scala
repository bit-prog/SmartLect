package ru.smartlect.orchestrator.incident

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

import java.time.Clock
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Периодически отправляет команде неотправленные инциденты.
 * Инцидент сохраняется в БД вместе с падением лекции, а уведомление уходит отдельно:
 * недоступность Telegram не мешает обработке, неотправленное будет отправлено позже.
 */
@Component
class IncidentNotifier(
    incidents: IncidentRepository,
    telegram: TelegramClient,
    props: TelegramProperties,
    tx: TransactionTemplate,
    clock: Clock
):
  private val log = LoggerFactory.getLogger(classOf[IncidentNotifier])

  @Scheduled(fixedDelayString = "${smartlect.incidents.notify-interval-ms:30000}")
  def notifyPending(): Unit =
    val pending = tx.execute(_ => incidents.findTop20ByNotifiedAtIsNullOrderByCreatedAt().asScala.toSeq)
    pending.foreach { incident =>
      try
        val text = format(incident)
        if props.enabled then telegram.send(text)
        else log.error("Incident (Telegram is not configured):\n{}", text)
        tx.executeWithoutResult { _ =>
          incidents.findById(incident.id).ifPresent(i => i.notifiedAt = clock.instant())
        }
      catch case NonFatal(e) => log.warn("Failed to send incident {}, will retry", incident.id, e)
    }

  private def format(incident: Incident): String =
    s"""🔴 SmartLect: обработка лекции упала
       |Лекция: ${incident.lectureId}
       |Этап: ${incident.stage}, попытка ${incident.attempt}
       |Ошибка: ${incident.errorMessage.take(1000)}""".stripMargin
