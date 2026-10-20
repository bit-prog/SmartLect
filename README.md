# SmartLect

Запись лекции (аудио + фото доски) → конспект в Markdown с формулами LaTeX.

- `docs/requirements.md` — требования, API, план
- `docs/data-model.md` — схема БД и ключи в S3
- `service/orchestrator` — API и управление обработкой (Scala 3, Spring Boot)
- `service/ml-worker` — распознавание и генерация (Python)
- `client` — приложение для iOS, Android и macOS (Flutter)

## Локальный запуск

```bash
docker compose up -d                                   # Postgres, RabbitMQ, S3

cd service/orchestrator && ./mvnw spring-boot:run      # API на http://localhost:8081

cd service/ml-worker && WORKER_FAKE=1 .venv/bin/python -m smartlect_worker   # см. README воркера

cd client && flutter run -d macos                       # см. README клиента
```

Тесты оркестратора (нужен запущенный docker compose): `cd service/orchestrator && ./mvnw test`.

| Сервис | Адрес |
|---|---|
| API | http://localhost:8081 |
| RabbitMQ (панель) | http://localhost:15672, `smartlect` / `smartlect` |
| PostgreSQL | `localhost:5433`, БД/пользователь/пароль `smartlect` |
| S3 | http://localhost:9100, ключи в `infra/s3.json` |
