-- Начальная схема SmartLect. Описание полей: docs/data-model.md

CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(320) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL
);

CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL
);

CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);

CREATE TABLE lectures (
    id                 uuid PRIMARY KEY,
    owner_id           uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title              varchar(255) NOT NULL,
    source             varchar(32)  NOT NULL
        CHECK (source IN ('MOBILE', 'BROWSER_EXTENSION', 'MEETING', 'OTHER')),
    recorded_at        timestamptz  NOT NULL,
    duration_ms        bigint CHECK (duration_ms >= 0),
    status             varchar(16)  NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'UPLOADED', 'QUEUED', 'PROCESSING', 'DONE', 'FAILED')),
    stage              varchar(16)
        CHECK (stage IN ('TRANSCRIBING', 'READING_BOARD', 'GENERATING', 'ASSEMBLING')),
    progress           smallint     NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    attempt            smallint     NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    audio_key          varchar(512),
    audio_content_type varchar(100),
    audio_size_bytes   bigint CHECK (audio_size_bytes >= 0),
    error_message      text,
    submitted_at       timestamptz,
    completed_at       timestamptz,
    version            bigint       NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL
);

CREATE INDEX lectures_owner_created_idx ON lectures (owner_id, created_at DESC);

CREATE TABLE board_photos (
    id           uuid PRIMARY KEY,
    lecture_id   uuid         NOT NULL REFERENCES lectures (id) ON DELETE CASCADE,
    offset_ms    bigint       NOT NULL CHECK (offset_ms >= 0),
    object_key   varchar(512) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes   bigint       NOT NULL CHECK (size_bytes >= 0),
    created_at   timestamptz  NOT NULL
);

CREATE INDEX board_photos_lecture_offset_idx ON board_photos (lecture_id, offset_ms);

CREATE TABLE incidents (
    id            uuid PRIMARY KEY,
    lecture_id    uuid        NOT NULL REFERENCES lectures (id) ON DELETE CASCADE,
    stage         varchar(16) NOT NULL
        CHECK (stage IN ('TRANSCRIBING', 'READING_BOARD', 'GENERATING', 'ASSEMBLING')),
    attempt       smallint    NOT NULL,
    error_message text        NOT NULL,
    details       text,
    notified_at   timestamptz,
    created_at    timestamptz NOT NULL
);

CREATE INDEX incidents_lecture_id_idx ON incidents (lecture_id);
