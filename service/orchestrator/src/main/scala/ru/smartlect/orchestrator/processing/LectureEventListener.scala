package ru.smartlect.orchestrator.processing

import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

/**
 * Принимает события воркеров. Ошибки обработки повторяются Spring AMQP
 * (spring.rabbitmq.listener.simple.retry.*), после чего сообщение уходит в `smartlect.dead`.
 */
@Component
class LectureEventListener(processing: ProcessingService):

  @RabbitListener(queues = Array(Queues.Events))
  def onEvent(event: LectureEvent): Unit = processing.handle(event)
