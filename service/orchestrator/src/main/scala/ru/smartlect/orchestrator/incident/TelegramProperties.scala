package ru.smartlect.orchestrator.incident

import org.springframework.boot.context.properties.ConfigurationProperties

import scala.beans.BeanProperty

/** Бот для уведомлений команды: `smartlect.telegram.*`. Пустой токен — уведомления только в лог. */
@ConfigurationProperties(prefix = "smartlect.telegram")
class TelegramProperties:
  @BeanProperty var botToken: String = ""
  /** Id чата или группы, куда бот пишет. */
  @BeanProperty var chatId: String = ""

  def enabled: Boolean = botToken.nonEmpty && chatId.nonEmpty
