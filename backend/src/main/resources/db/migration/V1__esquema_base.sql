-- =====================================================================
-- V1: extension pgvector y esquema base de la plataforma.
-- Las tablas son las de la seccion 10 del enunciado, en espanol y snake_case.
-- Las claves primarias son BIGSERIAL.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- investigaciones: raiz del agregado. El estado vive aqui y no en el
-- historial de conversacion, para poder reanudar tras una caida.
-- ---------------------------------------------------------------------
CREATE TABLE investigaciones (
    id                  BIGSERIAL PRIMARY KEY,
    objetivo            TEXT        NOT NULL,
    estado              VARCHAR(32) NOT NULL,
    ronda_actual        INTEGER     NOT NULL DEFAULT 0,
    max_rondas          INTEGER     NOT NULL DEFAULT 3,
    presupuesto_tokens  BIGINT      NOT NULL DEFAULT 200000,
    tokens_consumidos   BIGINT      NOT NULL DEFAULT 0,
    configuracion_json  TEXT,
    motivo_fallo        TEXT,
    creado_en           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actualizado_en      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finalizado_en      TIMESTAMPTZ,
    CONSTRAINT chk_investigaciones_estado
        CHECK (estado IN ('CREATED', 'PLANNING', 'RESEARCHING', 'VERIFYING', 'SYNTHESIZING',
                          'REVIEWING', 'COMPLETED', 'INTERRUPTED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_investigaciones_ronda
        CHECK (ronda_actual >= 0 AND ronda_actual <= max_rondas),
    CONSTRAINT chk_investigaciones_presupuesto
        CHECK (presupuesto_tokens >= 0 AND tokens_consumidos >= 0)
);

CREATE INDEX idx_investigaciones_estado ON investigaciones (estado);
CREATE INDEX idx_idx_investigaciones_creado ON investigaciones (creado_en DESC);

-- ---------------------------------------------------------------------
-- tareas_investigacion: una tarea produce evidencia o se declara fallida.
-- ---------------------------------------------------------------------
CREATE TABLE tareas_investigacion (
    id                 BIGSERIAL PRIMARY KEY,
    investigacion_id   BIGINT      NOT NULL REFERENCES investigaciones (id) ON DELETE CASCADE,
    descripcion        TEXT        NOT NULL,
    tipo_fuente        VARCHAR(16) NOT NULL,
    prioridad          INTEGER     NOT NULL DEFAULT 0,
    estado             VARCHAR(32) NOT NULL,
    ronda              INTEGER     NOT NULL DEFAULT 0,
    intentos           INTEGER     NOT NULL DEFAULT 0,
    resumen_resultado  TEXT,
    creado_en          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actualizado_en     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tareas_tipo_fuente
        CHECK (tipo_fuente IN ('INTERNA', 'WEB', 'BASE_DATOS')),
    CONSTRAINT chk_tareas_estado
        CHECK (estado IN ('PENDIENTE', 'EN_CURSO', 'COMPLETADA', 'FALLIDA', 'DESCARTADA'))
);

CREATE INDEX idx_tareas_investigacion ON tareas_investigacion (investigacion_id, estado);
CREATE INDEX idx_tareas_ronda ON tareas_investigacion (investigacion_id, ronda);

-- ---------------------------------------------------------------------
-- documentos y fragmentos: conocimiento interno con pgvector.
-- La dimension del embedding la fija el modelo configurado.
-- ---------------------------------------------------------------------
CREATE TABLE documentos (
    id              BIGSERIAL PRIMARY KEY,
    nombre          TEXT        NOT NULL,
    tipo            VARCHAR(16) NOT NULL,
    contenido_texto TEXT        NOT NULL,
    hash            TEXT        NOT NULL,
    creado_en       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_documentos_hash UNIQUE (hash),
    CONSTRAINT chk_documentos_tipo CHECK (tipo IN ('TXT', 'MD', 'PDF'))
);

CREATE INDEX idx_documentos_nombre ON documentos (nombre);

-- vector(768) corresponde a nomic-embed-text, el modelo por defecto.
-- Si se cambia de modelo de embeddings, una migracion nueva altera la dimension.
CREATE TABLE fragmentos_documento (
    id          BIGSERIAL PRIMARY KEY,
    documento_id BIGINT      NOT NULL REFERENCES documentos (id) ON DELETE CASCADE,
    indice      INTEGER     NOT NULL,
    texto       TEXT        NOT NULL,
    embedding   vector(768) NOT NULL,
    CONSTRAINT uq_fragmentos_documento_indice UNIQUE (documento_id, indice)
);

-- Indice de similitud coseno: es el que usa la busqueda por defecto.
CREATE INDEX idx_fragmentos_embedding
    ON fragmentos_documento USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ---------------------------------------------------------------------
-- fuentes: de donde salio la informacion, con el texto extraido completo
-- para poder verificar citas de forma determinista.
-- ---------------------------------------------------------------------
CREATE TABLE fuentes (
    id              BIGSERIAL PRIMARY KEY,
    investigacion_id BIGINT      NOT NULL REFERENCES investigaciones (id) ON DELETE CASCADE,
    tipo            VARCHAR(16) NOT NULL,
    referencia      TEXT        NOT NULL,
    titulo          TEXT,
    texto_extraido  TEXT,
    hash            TEXT,
    obtenida_en     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_fuentes_tipo CHECK (tipo IN ('DOCUMENTO', 'WEB', 'BASE_DATOS', 'LOCALRAG'))
);

CREATE INDEX idx_fuentes_investigacion ON fuentes (investigacion_id);

-- ---------------------------------------------------------------------
-- evidencias: afirmacion + cita textual + veredicto de verificacion.
-- ---------------------------------------------------------------------
CREATE TABLE evidencias (
    id                  BIGSERIAL PRIMARY KEY,
    investigacion_id    BIGINT      NOT NULL REFERENCES investigaciones (id) ON DELETE CASCADE,
    tarea_id            BIGINT      REFERENCES tareas_investigacion (id) ON DELETE SET NULL,
    fuente_id           BIGINT      NOT NULL REFERENCES fuentes (id) ON DELETE CASCADE,
    cita_textual        TEXT        NOT NULL,
    afirmacion          TEXT        NOT NULL,
    estado_verificacion VARCHAR(24) NOT NULL DEFAULT 'PENDIENTE',
    confianza           DOUBLE PRECISION,
    justificacion       TEXT,
    creado_en           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_evidencias_estado
        CHECK (estado_verificacion IN ('PENDIENTE', 'VERIFICADA', 'NO_VERIFICADA', 'PARCIAL')),
    CONSTRAINT chk_evidencias_confianza
        CHECK (confianza IS NULL OR (confianza >= 0.0 AND confianza <= 1.0))
);

CREATE INDEX idx_evidencias_investigacion ON evidencias (investigacion_id, estado_verificacion);
CREATE INDEX idx_evidencias_tarea ON evidencias (tarea_id);
CREATE INDEX idx_evidencias_fuente ON evidencias (fuente_id);

-- ---------------------------------------------------------------------
-- hallazgos y su relacion con evidencias verificadas
-- ---------------------------------------------------------------------
CREATE TABLE hallazgos (
    id              BIGSERIAL PRIMARY KEY,
    investigacion_id BIGINT      NOT NULL REFERENCES investigaciones (id) ON DELETE CASCADE,
    titulo          TEXT        NOT NULL,
    descripcion     TEXT,
    confianza       DOUBLE PRECISION,
    creado_en       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_hallazgos_confianza
        CHECK (confianza IS NULL OR (confianza >= 0.0 AND confianza <= 1.0))
);

CREATE INDEX idx_hallazgos_investigacion ON hallazgos (investigacion_id);

CREATE TABLE hallazgo_evidencia (
    hallazgo_id  BIGINT NOT NULL REFERENCES hallazgos (id) ON DELETE CASCADE,
    evidencia_id BIGINT NOT NULL REFERENCES evidencias (id) ON DELETE CASCADE,
    PRIMARY KEY (hallazgo_id, evidencia_id)
);

CREATE INDEX idx_hallazgo_evidencia_evidencia ON hallazgo_evidencia (evidencia_id);

-- ---------------------------------------------------------------------
-- contradicciones y preguntas abiertas
-- ---------------------------------------------------------------------
CREATE TABLE contradicciones (
    id              BIGSERIAL PRIMARY KEY,
    investigacion_id BIGINT      NOT NULL REFERENCES investigaciones (id) ON DELETE CASCADE,
    evidencia_a_id  BIGINT      NOT NULL REFERENCES evidencias (id) ON DELETE CASCADE,
    evidencia_b_id  BIGINT      NOT NULL REFERENCES evidencias (id) ON DELETE CASCADE,
    descripcion     TEXT        NOT NULL,
    resuelta        BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_contradicciones_distintas CHECK (evidencia_a_id <> evidencia_b_id)
);

CREATE INDEX idx_contradicciones_investigacion ON contradicciones (investigacion_id);

CREATE TABLE preguntas_abiertas (
    id              BIGSERIAL PRIMARY KEY,
    investigacion_id BIGINT      NOT NULL REFERENCES investigaciones (id) ON DELETE CASCADE,
    pregunta        TEXT        NOT NULL,
    estado          VARCHAR(24) NOT NULL DEFAULT 'ABIERTA',
    CONSTRAINT chk_preguntas_estado CHECK (estado IN ('ABIERTA', 'RESPONDIDA', 'DESCARTADA'))
);

CREATE INDEX idx_preguntas_investigacion ON preguntas_abiertas (investigacion_id);

-- ---------------------------------------------------------------------
-- informes: versiones del documento final, con sus advertencias
-- ---------------------------------------------------------------------
CREATE TABLE informes (
    id                BIGSERIAL PRIMARY KEY,
    investigacion_id  BIGINT      NOT NULL REFERENCES investigaciones (id) ON DELETE CASCADE,
    version           INTEGER     NOT NULL,
    contenido_markdown TEXT       NOT NULL,
    estado_revision   VARCHAR(24) NOT NULL DEFAULT 'BORRADOR',
    advertencias_json TEXT,
    creado_en         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_informes_version UNIQUE (investigacion_id, version),
    CONSTRAINT chk_informes_estado CHECK (estado_revision IN ('BORRADOR', 'EN_REVISION', 'APROBADO'))
);

CREATE INDEX idx_informes_investigacion ON informes (investigacion_id, version DESC);

-- ---------------------------------------------------------------------
-- Trazabilidad: cada llamada al LLM y cada ejecucion de tool queda registrada.
-- Es la base de la evaluacion y, mas adelante, de OpenTelemetry.
-- ---------------------------------------------------------------------
CREATE TABLE ejecuciones_agente (
    id               BIGSERIAL PRIMARY KEY,
    investigacion_id BIGINT      NOT NULL REFERENCES investigaciones (id) ON DELETE CASCADE,
    tarea_id         BIGINT      REFERENCES tareas_investigacion (id) ON DELETE SET NULL,
    agente           VARCHAR(32) NOT NULL,
    modelo           VARCHAR(128),
    version_prompt   VARCHAR(64),
    estado           VARCHAR(24) NOT NULL,
    tokens_entrada   INTEGER     NOT NULL DEFAULT 0,
    tokens_salida    INTEGER     NOT NULL DEFAULT 0,
    duracion_ms      BIGINT      NOT NULL DEFAULT 0,
    error            TEXT,
    creado_en        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ejecuciones_agente_estado
        CHECK (estado IN ('EN_CURSO', 'EXITOSA', 'FALLIDA'))
);

CREATE INDEX idx_ejecuciones_agente_investigacion ON ejecuciones_agente (investigacion_id, agente);
CREATE INDEX idx_ejecuciones_agente_tarea ON ejecuciones_agente (tarea_id);

CREATE TABLE ejecuciones_herramienta (
    id                 BIGSERIAL PRIMARY KEY,
    ejecucion_agente_id BIGINT      NOT NULL REFERENCES ejecuciones_agente (id) ON DELETE CASCADE,
    herramienta        VARCHAR(64) NOT NULL,
    parametros_json    TEXT,
    resultado_resumen  TEXT,
    estado             VARCHAR(24) NOT NULL,
    duracion_ms        BIGINT      NOT NULL DEFAULT 0,
    error              TEXT,
    creado_en          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ejecuciones_herramienta_estado
        CHECK (estado IN ('EN_CURSO', 'EXITOSA', 'FALLIDA', 'TIMEOUT'))
);

CREATE INDEX idx_ejecuciones_herramienta_agente ON ejecuciones_herramienta (ejecucion_agente_id);
CREATE INDEX idx_ejecuciones_herramienta_estado ON ejecuciones_herramienta (estado);

-- ---------------------------------------------------------------------
-- eventos: linea de tiempo persistida y base del stream SSE
-- ---------------------------------------------------------------------
CREATE TABLE eventos_investigacion (
    id               BIGSERIAL PRIMARY KEY,
    investigacion_id BIGINT      NOT NULL REFERENCES investigaciones (id) ON DELETE CASCADE,
    tipo             VARCHAR(64) NOT NULL,
    payload_json     TEXT,
    creado_en        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_eventos_investigacion ON eventos_investigacion (investigacion_id, id);
