package ru.smartlect.orchestrator.incident

import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

import scala.jdk.CollectionConverters.*

/** Отправка сообщений через Telegram Bot API (метод sendMessage). */
@Component
class TelegramClient(props: TelegramProperties):
  private val client = RestClient.create("https://api.telegram.org")

  def send(text: String): Unit =
    client.post()
      .uri("/bot{token}/sendMessage", props.botToken)
      .contentType(MediaType.APPLICATION_JSON)
      .body(Map("chat_id" -> props.chatId, "text" -> text).asJava)
      .retrieve()
      .toBodilessEntity()
