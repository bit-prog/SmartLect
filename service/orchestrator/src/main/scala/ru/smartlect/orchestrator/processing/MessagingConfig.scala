package ru.smartlect.orchestrator.processing

import org.springframework.amqp.core.*
import org.springframework.amqp.support.converter.{JacksonJsonMessageConverter, MessageConverter}
import org.springframework.context.annotation.{Bean, Configuration}
import ru.smartlect.orchestrator.lecture.ProcessingStage
import tools.jackson.databind.json.JsonMapper

/**
 * Топология RabbitMQ. Её объявляет только оркестратор; воркер подключается к готовым очередям.
 *
 *  - exchange `smartlect` (direct): очередь задач на каждый этап + очередь событий;
 *  - exchange `smartlect.dlx`: сообщения, которые не удалось обработать, попадают в `smartlect.dead`.
 */
@Configuration
class MessagingConfig:

  @Bean
  def messageConverter(jsonMapper: JsonMapper): MessageConverter = JacksonJsonMessageConverter(jsonMapper)

  @Bean
  def topology(): Declarables =
    val exchange = ExchangeBuilder.directExchange(Queues.Exchange).durable(true).build[DirectExchange]()
    val deadExchange = ExchangeBuilder.fanoutExchange(Queues.DeadLetterExchange).durable(true).build[FanoutExchange]()
    val deadQueue = QueueBuilder.durable(Queues.Dead).build()

    def queue(name: String) = QueueBuilder.durable(name).deadLetterExchange(Queues.DeadLetterExchange).build()

    val taskQueues = ProcessingStage.values.toSeq.map(stage => queue(Queues.task(stage)))
    val eventsQueue = queue(Queues.Events)

    val declarables: Seq[Declarable] =
      Seq(exchange, deadExchange, deadQueue, BindingBuilder.bind(deadQueue).to(deadExchange), eventsQueue,
        BindingBuilder.bind(eventsQueue).to(exchange).`with`(Queues.Events))
        ++ taskQueues.flatMap(q => Seq(q, BindingBuilder.bind(q).to(exchange).`with`(q.getName)))
    Declarables(declarables*)

/** Имена exchange и очередей; routing key совпадает с именем очереди. `final val` — чтобы можно было использовать в аннотациях. */
object Queues:
  final val Exchange = "smartlect"
  final val DeadLetterExchange = "smartlect.dlx"
  final val Dead = "smartlect.dead"
  final val Events = "lecture.events"

  def task(stage: ProcessingStage): String = stage match
    case ProcessingStage.TRANSCRIBING  => "lecture.transcribe"
    case ProcessingStage.READING_BOARD => "lecture.board"
    case ProcessingStage.GENERATING    => "lecture.generate"
    case ProcessingStage.ASSEMBLING    => "lecture.assemble"
