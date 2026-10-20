# SmartLect — модель данных

Описание схемы БД. Реализация: JPA-сущности, репозитории и миграции
Flyway (`service/orchestrator/src/main/resources/db/migration/V1__init.sql`).
Схема Hibernate не генерирует (`ddl-auto=validate`), её создаёт только Flyway.

Пакет: `ru.smartlect.orchestrator`. Предлагаемая раскладка:
`user/`, `auth/`, `lecture/`, `incident/` — в каждом сущность + репозиторий.

## Общие правила

- Первичные ключи — `uuid` (генерирует приложение или `gen_random_uuid()`).
- Время — `timestamptz`, в коде `java.time.Instant`.
- Перечисления хранятся строкой (`varchar` + `@Enumerated(EnumType.STRING)`),
  в БД можно добавить `CHECK` на допустимые значения.
- `created_at` / `updated_at` есть во всех основных таблицах.

## Перечисления

| Enum | Значения |
|---|---|
| `LectureStatus` | `CREATED`, `UPLOADED`, `QUEUED`, `PROCESSING`, `DONE`, `FAILED` |
| `ProcessingStage` | `TRANSCRIBING`, `READING_BOARD`, `GENERATING`, `ASSEMBLING` |
| `LectureSource` | `MOBILE`, `BROWSER_EXTENSION`, `MEETING`, `OTHER` |

## Таблицы

### `users`
| Поле | Тип | Ограничения | Комментарий |
|---|---|---|---|
| `id` | uuid | PK | |
| `email` | varchar(320) | NOT NULL, UNIQUE | хранить в нижнем регистре |
| `password_hash` | varchar(100) | NOT NULL | BCrypt |
| `created_at` | timestamptz | NOT NULL | |
| `updated_at` | timestamptz | NOT NULL | |

### `refresh_tokens`
| Поле | Тип | Ограничения | Комментарий |
|---|---|---|---|
| `id` | uuid | PK | |
| `user_id` | uuid | NOT NULL, FK → users ON DELETE CASCADE | |
| `token_hash` | varchar(64) | NOT NULL, UNIQUE | SHA-256 от токена; сам токен не храним |
| `expires_at` | timestamptz | NOT NULL | |
| `revoked_at` | timestamptz | NULL | заполняется при выходе / ротации |
| `created_at` | timestamptz | NOT NULL | |

Индекс: `(user_id)`.

### `lectures`
| Поле | Тип | Ограничения | Комментарий |
|---|---|---|---|
| `id` | uuid | PK | |
| `owner_id` | uuid | NOT NULL, FK → users ON DELETE CASCADE | |
| `title` | varchar(255) | NOT NULL | |
| `source` | varchar(32) | NOT NULL | `LectureSource` |
| `recorded_at` | timestamptz | NOT NULL | когда шла лекция |
| `duration_ms` | bigint | NULL | длительность аудио, нужна для процентов |
| `status` | varchar(16) | NOT NULL | `LectureStatus`, по умолчанию `CREATED` |
| `stage` | varchar(16) | NULL | `ProcessingStage`, текущий этап |
| `progress` | smallint | NOT NULL, 0–100 | процент текущего этапа |
| `attempt` | smallint | NOT NULL, default 0 | попытка текущего этапа (для ретрая) |
| `audio_key` | varchar(512) | NULL | ключ в S3, NULL пока не загружено |
| `audio_content_type` | varchar(100) | NULL | |
| `audio_size_bytes` | bigint | NULL | |
| `error_message` | text | NULL | последняя ошибка, показывается пользователю |
| `submitted_at` | timestamptz | NULL | |
| `completed_at` | timestamptz | NULL | |
| `version` | bigint | NOT NULL | `@Version` — оптимистичная блокировка, т.к. статус меняют и API, и события воркера |
| `created_at` | timestamptz | NOT NULL | |
| `updated_at` | timestamptz | NOT NULL | |

Индекс: `(owner_id, created_at DESC)` — для списка лекций пользователя.

Результаты обработки (расшифровка, текст доски, конспект) лежат в S3 по
фиксированным ключам (см. ниже), поэтому в таблице их ключи не хранятся.

### `board_photos`
| Поле | Тип | Ограничения | Комментарий |
|---|---|---|---|
| `id` | uuid | PK | |
| `lecture_id` | uuid | NOT NULL, FK → lectures ON DELETE CASCADE | |
| `offset_ms` | bigint | NOT NULL, ≥ 0 | таймкод от начала записи |
| `object_key` | varchar(512) | NOT NULL | ключ в S3 |
| `content_type` | varchar(100) | NOT NULL | |
| `size_bytes` | bigint | NOT NULL | |
| `created_at` | timestamptz | NOT NULL | |

Индекс: `(lecture_id, offset_ms)`.

### `incidents`
| Поле | Тип | Ограничения | Комментарий |
|---|---|---|---|
| `id` | uuid | PK | |
| `lecture_id` | uuid | NOT NULL, FK → lectures ON DELETE CASCADE | |
| `stage` | varchar(16) | NOT NULL | на каком этапе упало |
| `attempt` | smallint | NOT NULL | |
| `error_message` | text | NOT NULL | |
| `details` | text | NULL | стектрейс / лог воркера |
| `notified_at` | timestamptz | NULL | когда ушло уведомление в Telegram |
| `created_at` | timestamptz | NOT NULL | |

## Ключи в S3 (бакет `lectures`)

Генерируются в `ru.smartlect.orchestrator.storage.StorageKeys`:

| Объект | Ключ |
|---|---|
| Аудио | `lectures/{lectureId}/audio` |
| Фото доски | `lectures/{lectureId}/photos/{photoId}` |
| Расшифровка | `lectures/{lectureId}/transcript.json` |
| Текст с доски | `lectures/{lectureId}/board.json` |
| Разделы конспекта | `lectures/{lectureId}/sections.json` |
| Конспект | `lectures/{lectureId}/notes.md` |

Удаление лекции = удаление строки (каскадом фото и инциденты) +
`ObjectStorage.deletePrefix(StorageKeys.lecturePrefix(id))`.

## Подсказки по Scala + JPA

- Сущность — обычный `class` (не `case class`), с конструктором без аргументов
  и полями `@BeanProperty var`; Hibernate нужны изменяемые поля и прокси.
- Nullable-поля в сущности держать как `null`, а наружу (в DTO) отдавать `Option`.
- Репозиторий — `trait LectureRepository extends JpaRepository[Lecture, UUID]`.
