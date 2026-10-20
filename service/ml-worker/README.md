# SmartLect ML worker

Python-воркер: берёт задачи этапов из RabbitMQ, читает и пишет файлы в S3, отчитывается
событиями в `lecture.events`. Формат сообщений — `smartlect_worker/messages.py`
(зеркало `processing/Messages.scala` в оркестраторе).

| Этап | Очередь | Вход → выход | Модель |
|---|---|---|---|
| `TRANSCRIBING` | `lecture.transcribe` | аудио → `transcript.json` | faster-whisper |
| `READING_BOARD` | `lecture.board` | фото → `board.json` | Tesseract |
| `GENERATING` | `lecture.generate` | расшифровка + доска → `sections.json` (кусками по 12 мин) | Ollama |
| `ASSEMBLING` | `lecture.assemble` | разделы → `notes.md` (заголовок, оглавление) | Ollama |

## Запуск

```bash
python3.13 -m venv .venv
.venv/bin/pip install -e '.[dev]'

# Без моделей — заглушки, чтобы проверить весь конвейер
WORKER_FAKE=1 .venv/bin/python -m smartlect_worker

# Тесты
.venv/bin/pytest
```

Оркестратор должен быть запущен: очереди создаёт он, воркер ждёт их появления.

## Настоящие модели

```bash
.venv/bin/pip install -e '.[whisper,ocr]'
brew install tesseract tesseract-lang   # OCR с русским языком
brew install ollama && ollama serve     # в отдельном терминале
ollama pull qwen2.5:7b

WHISPER_MODEL=large-v3-turbo WHISPER_LANGUAGE=ru .venv/bin/python -m smartlect_worker
```

Модели можно разнести по машинам — каждый процесс берёт только свои этапы:

```bash
# MacBook: распознавание речи и доски
WORKER_STAGES=transcribe,board .venv/bin/python -m smartlect_worker
# Машина с 32 ГБ: генерация конспекта
WORKER_STAGES=generate,assemble OLLAMA_MODEL=qwen2.5:14b .venv/bin/python -m smartlect_worker
```

(Для второй машины в `RABBITMQ_URL` и `S3_ENDPOINT` указать адрес первой.)

## Переменные окружения

| Переменная | По умолчанию | |
|---|---|---|
| `RABBITMQ_URL` | `amqp://smartlect:smartlect@localhost:5672/%2F` | |
| `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | docker compose | |
| `WORKER_STAGES` | `all` | `transcribe,board,generate,assemble` |
| `WORKER_FAKE` | `0` | `1` — заглушки вместо моделей |
| `WHISPER_MODEL` | `small` | `large-v3-turbo` — точнее, медленнее |
| `WHISPER_LANGUAGE` | авто | `ru` |
| `TESSERACT_LANG` | `rus+eng` | |
| `OLLAMA_URL`, `OLLAMA_MODEL` | `localhost:11434`, `qwen2.5:7b` | |
| `OLLAMA_NUM_CTX` | `16384` | контекст модели |
| `CHUNK_MINUTES` | `12` | длина куска лекции на один раздел |
