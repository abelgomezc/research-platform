# Esquema de base de datos

PostgreSQL 16 con la extension `pgvector`. Esquema aplicado por Flyway desde
`backend/src/main/resources/db/migration/V1__esquema_base.sql`.

Convenciones:

- Tablas y columnas en espanol, `snake_case`.
- Claves primarias `BIGSERIAL` (Java `Long`).
- Estados como `VARCHAR` con `CHECK` que refleja el enum de Java.
- Llaves foraneas fisicas e indices en todas las columnas de busqueda.

## Diagrama

```mermaid
erDiagram
    investigaciones ||--o{ tareas_investigacion : "planifica"
    investigaciones ||--o{ fuentes : "reúne"
    investigaciones ||--o{ evidencias : "sostiene"
    investigaciones ||--o{ hallazgos : "concluye"
    investigaciones ||--o{ contradicciones : "detecta"
    investigaciones ||--o{ preguntas_abiertas : "deja abiertas"
    investigaciones ||--o{ informes : "produce"
    investigaciones ||--o{ ejecuciones_agente : "registra"
    investigaciones ||--o{ eventos_investigacion : "emite"

    tareas_investigacion ||--o{ evidencias : "produce"
    documentos ||--o{ fragmentos_documento : "divide en"

    fuentes ||--o{ evidencias : "respalda"
    hallazgos ||--o{ hallazgo_evidencia : "se apoya en"
    evidencias ||--o{ hallazgo_evidencia : "sustenta"

    contradicciones }o--|| evidencias : "evidencia_a"
    contradicciones }o--|| evidencias : "evidencia_b"

    ejecuciones_agente ||--o{ ejecuciones_herramienta : "invoca"

    investigaciones {
        bigserial id PK
        text objetivo
        varchar estado "CREATED..CANCELLED"
        integer ronda_actual
        integer max_rondas
        bigint presupuesto_tokens
        bigint tokens_consumidos
        text configuracion_json "modelos y versiones de prompt"
        text motivo_fallo
        timestamptz creado_en
        timestamptz actualizado_en
        timestamptz finalizado_en
    }

    tareas_investigacion {
        bigserial id PK
        bigint investigacion_id FK
        text descripcion
        varchar tipo_fuente "INTERNA, WEB, BASE_DATOS"
        integer prioridad
        varchar estado
        integer ronda
        integer intentos
        text resumen_resultado
    }

    documentos {
        bigserial id PK
        text nombre
        varchar tipo "TXT, MD, PDF"
        text contenido_texto
        text hash UK
    }

    fragmentos_documento {
        bigserial id PK
        bigint documento_id FK
        integer indice
        text texto
        vector embedding "vector(768)"
    }

    fuentes {
        bigserial id PK
        bigint investigacion_id FK
        varchar tipo "DOCUMENTO, WEB, BASE_DATOS, LOCALRAG"
        text referencia
        text titulo
        text texto_extraido
        text hash
    }

    evidencias {
        bigserial id PK
        bigint investigacion_id FK
        bigint tarea_id FK
        bigint fuente_id FK
        text cita_textual
        text afirmacion
        varchar estado_verificacion
        double confianza
        text justificacion
    }

    hallazgos {
        bigserial id PK
        bigint investigacion_id FK
        text titulo
        text descripcion
        double confianza
    }

    hallazgo_evidencia {
        bigint hallazgo_id PK,FK
        bigint evidencia_id PK,FK
    }

    contradicciones {
        bigserial id PK
        bigint investigacion_id FK
        bigint evidencia_a_id FK
        bigint evidencia_b_id FK
        text descripcion
        boolean resuelta
    }

    preguntas_abiertas {
        bigserial id PK
        bigint investigacion_id FK
        text pregunta
        varchar estado
    }

    informes {
        bigserial id PK
        bigint investigacion_id FK
        integer version
        text contenido_markdown
        varchar estado_revision
        text advertencias_json
    }

    ejecuciones_agente {
        bigserial id PK
        bigint investigacion_id FK
        bigint tarea_id FK
        varchar agente
        varchar modelo
        varchar version_prompt
        varchar estado
        integer tokens_entrada
        integer tokens_salida
        bigint duracion_ms
        text error
    }

    ejecuciones_herramienta {
        bigserial id PK
        bigint ejecucion_agente_id FK
        varchar herramienta
        text parametros_json
        text resultado_resumen
        varchar estado
        bigint duracion_ms
        text error
    }

    eventos_investigacion {
        bigserial id PK
        bigint investigacion_id FK
        varchar tipo
        text payload_json
    }
```

## Notas de diseno

### Por que `fuentes.texto_extraido` completo

La verificacion determinista necesita comprobar que la cita textual existe
literalmente en el texto de la fuente. Guardar solo un resumen o un snippet
imposibilitaria esa comprobacion, que es precisamente la garantia fuerte del
sistema.

### Por que `hallazgo_evidencia` es tabla pivote y no una columna

Un hallazgo se apoya en varias evidencias y una evidencia puede sustentar varios
hallazgos. La relacion es de muchos a muchos.

### Por que el estado es `VARCHAR` con `CHECK` y no un enum de PostgreSQL

Un `ENUM` de PostgreSQL requiere `ALTER TYPE` para anadir valores, lo que es
fragil con Flyway y bloqueante. El `CHECK` documenta el catalogo en el esquema y
se cambia con un `ALTER TABLE ... DROP CONSTRAINT / ADD CONSTRAINT`, que es una
migracion normal.

### Indice vectorial

```sql
CREATE INDEX idx_fragmentos_embedding
    ON fragmentos_documento USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

Es un indice approximate (IVFFlat), que es la opcion por defecto sensata para un
corpus pequeno sin maintenance. Si el corpus crece, la alternativa exacta seria
HNSW, que se evaluaria con numeros reales en la Fase 2.

**Requisito:** la columna es `vector(768)`, la dimension de `nomic-embed-text`.
Cambiar de modelo de embeddings exige una migracion nueva que altere la dimension
y regenere los embeddings: nunca se edita una migracion aplicada.

### Sobre el usuario de solo lectura

La tool `query_database` (Fase 3) usara un usuario de PostgreSQL con solo `SELECT`.
La tabla del dataset historico de demostracion se crea en la Fase 2.

## Migraciones

| Version | Archivo | Contenido |
|---|---|---|
| 1 | `V1__esquema_base.sql` | Extension pgvector y esquema core |

Regla: **una migracion aplicada no se modifica**. Para corregir algo se crea
`V2__...` con el `ALTER` correspondiente.
