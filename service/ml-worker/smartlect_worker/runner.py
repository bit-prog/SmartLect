"""
Цикл воркера: забирает задачи из очередей этапов, выполняет и отчитывается событиями.

Задача выполняется в отдельном потоке, а главный поток держит соединение с RabbitMQ
(heartbeat): иначе многоминутное распознавание оборвало бы соединение. Всё, что трогает
канал из рабочего потока, передаётся в главный через add_callback_threadsafe.

Сообщение подтверждается (ack) только после того, как отправлено событие COMPLETED/FAILED:
если воркер упадёт посреди задачи, RabbitMQ отдаст её снова.
"""

from __future__ import annotations

import logging
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from functools import partial
from typing import Callable

import pika
from pika.adapters.blocking_connection import BlockingChannel

from .messages import EVENTS_ROUTING_KEY, EXCHANGE, Task, event
from .stages import assemble, board, generate, transcribe
from .stages.common import Context

log = logging.getLogger(__name__)

# этап → (очередь, обработчик); очереди объявляет оркестратор (processing/MessagingConfig.scala)
STAGES: dict[str, tuple[str, Callable]] = {
    "TRANSCRIBING": ("lecture.transcribe", transcribe.run),
    "READING_BOARD": ("lecture.board", board.run),
    "GENERATING": ("lecture.generate", generate.run),
    "ASSEMBLING": ("lecture.assemble", assemble.run),
}

ALIASES = {"transcribe": "TRANSCRIBING", "board": "READING_BOARD", "generate": "GENERATING", "assemble": "ASSEMBLING"}


def parse_stages(value: str) -> list[str]:
    if value.strip() == "all":
        return list(STAGES)
    return [ALIASES.get(s.strip(), s.strip().upper()) for s in value.split(",") if s.strip()]


class Worker:
    def __init__(self, ctx: Context, stages: list[str]):
        unknown = [s for s in stages if s not in STAGES]
        if unknown:
            raise ValueError(f"Unknown stages: {unknown}")
        self._ctx = ctx
        self._stages = stages
        # Одна задача за раз: модели тяжёлые, параллельность — запуском нескольких воркеров
        self._executor = ThreadPoolExecutor(max_workers=1)

    def run(self) -> None:
        while True:
            try:
                self._consume()
            except pika.exceptions.AMQPConnectionError as e:
                log.warning("RabbitMQ connection lost (%s), reconnecting in 5 s", e)
                time.sleep(5)

    def _consume(self) -> None:
        params = pika.URLParameters(self._ctx.config.rabbitmq_url)
        params.heartbeat = 60
        connection = pika.BlockingConnection(params)
        channel = connection.channel()
        channel.basic_qos(prefetch_count=1)

        for stage in self._stages:
            queue, handler = STAGES[stage]
            self._wait_for_queue(connection, queue)
            channel.basic_consume(queue, partial(self._on_message, connection, handler, stage))
        log.info("Waiting for tasks: %s", ", ".join(STAGES[s][0] for s in self._stages))
        channel.start_consuming()

    @staticmethod
    def _wait_for_queue(connection: pika.BlockingConnection, queue: str) -> None:
        """Очереди создаёт оркестратор при старте — ждём, пока он поднимется."""
        while True:
            probe = connection.channel()
            try:
                probe.queue_declare(queue, passive=True)
                probe.close()
                return
            except pika.exceptions.ChannelClosedByBroker:
                log.info("Queue %s does not exist yet, is the orchestrator running? Retrying in 5 s", queue)
                time.sleep(5)

    def _on_message(self, connection, handler, stage, channel: BlockingChannel, method, _props, body: bytes) -> None:
        try:
            task = Task.parse(body)
            if task.stage != stage:
                raise ValueError(f"Task for {task.stage} in the {stage} queue")
        except Exception:
            log.exception("Malformed task, sending to dead letter queue: %r", body[:500])
            channel.basic_nack(method.delivery_tag, requeue=False)
            return
        self._executor.submit(self._process, connection, channel, method.delivery_tag, handler, task)

    def _process(self, connection, channel: BlockingChannel, delivery_tag: int, handler, task: Task) -> None:
        def threadsafe(fn) -> None:
            try:
                connection.add_callback_threadsafe(fn)
            except Exception:
                # Соединение уже закрыто: задача вернётся в очередь и будет выполнена заново
                log.warning("Connection closed, cannot report lecture %s", task.lecture_id)

        def publish(body: bytes) -> None:
            threadsafe(partial(
                channel.basic_publish, EXCHANGE, EVENTS_ROUTING_KEY, body,
                pika.BasicProperties(content_type="application/json", delivery_mode=2)))

        log.info("Lecture %s: %s, attempt %d", task.lecture_id, task.stage, task.attempt)
        started = time.monotonic()
        try:
            result = handler(task, self._ctx, ProgressReporter(lambda p: publish(event(task, "PROGRESS", progress=p))))
            publish(event(task, "COMPLETED", durationMs=(result or {}).get("durationMs")))
            log.info("Lecture %s: %s done in %.1f s", task.lecture_id, task.stage, time.monotonic() - started)
        except Exception as e:
            log.exception("Lecture %s: %s failed", task.lecture_id, task.stage)
            publish(event(task, "FAILED", error=f"{type(e).__name__}: {e}"[:2000], details=traceback.format_exc()[-8000:]))
        finally:
            threadsafe(partial(channel.basic_ack, delivery_tag))


class ProgressReporter:
    """Превращает долю 0.0–1.0 в проценты и не шлёт событие чаще, чем раз в interval_s."""

    def __init__(self, send: Callable[[int], None], interval_s: float = 2.0):
        self._send = send
        self._interval = interval_s
        self._last_percent = -1
        self._last_time = 0.0

    def __call__(self, fraction: float) -> None:
        percent = max(0, min(99, int(fraction * 100)))  # 100 % — это событие COMPLETED
        now = time.monotonic()
        if percent > self._last_percent and now - self._last_time >= self._interval:
            self._last_percent, self._last_time = percent, now
            self._send(percent)
