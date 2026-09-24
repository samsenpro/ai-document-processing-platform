-- Esquema inicial de DocuMind AI.
-- Todos los identificadores son UUID generados por la aplicación (no revelan volumen ni orden).

CREATE TABLE organizations (
    id          UUID PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id),
    email           VARCHAR(254) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(120) NOT NULL,
    role            VARCHAR(20)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'USER'))
);
-- El email es único sin distinguir mayúsculas
CREATE UNIQUE INDEX uk_users_email ON users (LOWER(email));
CREATE INDEX ix_users_organization ON users (organization_id);

CREATE TABLE documents (
    id                 UUID PRIMARY KEY,
    organization_id    UUID         NOT NULL REFERENCES organizations (id),
    owner_id           UUID         NOT NULL REFERENCES users (id),
    original_filename  VARCHAR(255) NOT NULL,
    content_type       VARCHAR(100) NOT NULL,
    size_bytes         BIGINT       NOT NULL,
    checksum_sha256    VARCHAR(64)  NOT NULL,
    storage_key        VARCHAR(255) NOT NULL,
    requested_type     VARCHAR(20)  NOT NULL,
    detected_type      VARCHAR(20),
    status             VARCHAR(20)  NOT NULL,
    processing_time_ms BIGINT,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_documents_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_documents_size CHECK (size_bytes > 0),
    CONSTRAINT ck_documents_status CHECK (status IN ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_documents_requested_type CHECK (requested_type IN
        ('AUTO', 'INVOICE', 'CONTRACT', 'RESUME', 'RECEIPT', 'IDENTIFICATION', 'REPORT', 'OTHER')),
    CONSTRAINT ck_documents_detected_type CHECK (detected_type IN
        ('INVOICE', 'CONTRACT', 'RESUME', 'RECEIPT', 'IDENTIFICATION', 'REPORT', 'OTHER'))
);
-- Listados paginados por usuario (rol USER) y por organización (rol ADMIN), del más reciente al más antiguo
CREATE INDEX ix_documents_owner_created ON documents (owner_id, created_at DESC);
CREATE INDEX ix_documents_organization_created ON documents (organization_id, created_at DESC);
CREATE INDEX ix_documents_organization_status ON documents (organization_id, status);

CREATE TABLE document_processings (
    id              UUID PRIMARY KEY,
    document_id     UUID         NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    attempt         INT          NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    requested_type  VARCHAR(20)  NOT NULL,
    requested_by    UUID         REFERENCES users (id) ON DELETE SET NULL,
    correlation_id  VARCHAR(64),
    queued_at       TIMESTAMPTZ  NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,
    error_code      VARCHAR(50),
    error_message   VARCHAR(500),
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_processings_document_attempt UNIQUE (document_id, attempt),
    CONSTRAINT ck_processings_attempt CHECK (attempt > 0),
    CONSTRAINT ck_processings_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED'))
);
-- Garantía a nivel de base de datos: un documento no puede tener dos procesamientos activos a la vez
CREATE UNIQUE INDEX uk_processings_active ON document_processings (document_id)
    WHERE status IN ('QUEUED', 'RUNNING');
-- Búsqueda de procesamientos atascados (ver StaleProcessingReaper)
CREATE INDEX ix_processings_status_queued ON document_processings (status, queued_at)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE document_results (
    id                 UUID PRIMARY KEY,
    document_id        UUID          NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    processing_id      UUID          NOT NULL REFERENCES document_processings (id) ON DELETE CASCADE,
    document_type      VARCHAR(20)   NOT NULL,
    confidence         NUMERIC(4, 3) NOT NULL,
    language           VARCHAR(10),
    extracted_text     TEXT          NOT NULL,
    summary            TEXT          NOT NULL,
    -- Cada tipo de documento tiene campos distintos: JSONB en lugar de columnas fijas
    entities           JSONB         NOT NULL,
    metadata           JSONB         NOT NULL,
    processing_time_ms BIGINT        NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_results_document UNIQUE (document_id),
    CONSTRAINT ck_results_confidence CHECK (confidence BETWEEN 0 AND 1)
);

-- La auditoría sobrevive al borrado del documento: document_id no es clave foránea a propósito
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations (id),
    user_id         UUID        REFERENCES users (id) ON DELETE SET NULL,
    event           VARCHAR(40) NOT NULL,
    document_id     UUID,
    ip_address      VARCHAR(45),
    metadata        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_audit_organization_created ON audit_logs (organization_id, created_at DESC);
CREATE INDEX ix_audit_document ON audit_logs (document_id);
CREATE INDEX ix_audit_user ON audit_logs (user_id);
