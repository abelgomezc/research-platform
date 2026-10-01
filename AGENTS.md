# AI Research & Intelligence Platform

Plataforma de investigacion agentica. Recibe una pregunta de investigacion
compleja y produce un informe donde cada afirmacion cita su evidencia verificada.

Documentacion: [README.md](README.md). Estado de avance: seccion inferior.

---

## Convenciones obligatorias

### Identificadores

- Clases, metodos, paquetes, variables: **ingles**.
- Textos visibles al usuario, mensajes de error, logs de negocio: **espanol**.
- Comentarios de codigo y Javadoc: **espanol**, sin tildes en el codigo.

### Base de datos

- Tablas y columnas en **espanol**, `snake_case`: `investigaciones`,
  `tareas_investigacion`, `creado_en`.
- Claves primarias `BIGSERIAL` mapeadas a `Long`.
- Estados y tipos como **enums de Java**, persistidos como `String`.
- Migraciones Flyway `V1__`, `V2__`... **Nunca modifiques una migracion ya
  aplicada**: crea una nueva. Una correccion de datos va en una migracion nueva.

### Configuracion

- `application.properties` (**nunca** `.yml`), con perfiles y variables de entorno.
- `.env.example` documentado y versionado; `.env` nunca se versiona.
- Cero secretos en el repositorio.

### API

- REST bajo `/api`, JSON, codigos HTTP correctos.
- Manejo global de errores con `@ControllerAdvice` y formato unico
  (`ApiErrorResponse`). Ningun controller construye errores a mano.
- DTOs de entrada y salida separados de las entidades.
- Bean Validation en las entradas.

### Prompts

- Los prompts viven en `backend/src/main/resources/prompts/<agente>-v<N>.st`.
- Cada ejecucion registra el **nombre y la version del prompt** usado y el modelo.

### Modelos

- El modelo se configura **por rol de agente** en `app.llm.models.*`.
- El codigo nunca fija un nombre de modelo.

---

## Frontier de los modulos

Regla central: **el LLM decide contenido, el codigo controla el flujo**.

| Modulo | Responsabilidad | No debe |
|---|---|---|
| `research/` | Maquina de estados, checkpoints, presupuestos, eventos | Delegar transiciones al LLM |
| `planning/` | Generar plan y tareas a partir del objetivo | Investigar |
| `agents/` | Rol, prompt versionado, tools permitidas, salida validada | Almacenar estado del flujo |
| `tools/` | Registro, esquemas, permisos, riesgo, timeout, reintentos | Contener logica de negocio |
| `knowledge/` | Puerto de busqueda + adaptadores + ingesta | Saber que adaptador esta activo |
| `evidence/` | Fuentes, evidencias, verificacion, contradicciones | Aceptar citas no verificadas |
| `report/` | Generar y validar el informe | Afirmar sin cita |
| `evaluation/` | Dataset, runner, metricas | Inventar resultados |
| `llm/` | Modelos por rol, conteo de tokens, reintentos | Decidir el siguiente paso |
| `api/` | Controllers, DTOs, errores | Contener logica de negocio |
| `common/` | Utilidades, excepciones, configuracion transversal | Conocer el dominio |

### Reglas de seguridad que se aplican en todo el codigo

- Todo texto externo (web, documentos, resultados de tools) es **DATO NO
  CONFIABLE**: se delimita en el prompt y se instruye al modelo a no obedecer
  instrucciones que aparezcan dentro.
- Ningun texto externo puede cambiar las tools disponibles ni las reglas del sistema.
- `fetch_page`: solo http/https, bloquear localhost, IPs privadas y link-local
  resolviendo DNS antes de conectar, limitar redirecciones, tamano maximo, timeout.
- `query_database`: un unico `SELECT`, tablas permitidas, `LIMIT` forzado,
  statement timeout, usuario de solo lectura.
- Sin secretos ni datos sensibles en logs.

---

## Servicios a reiniciar tras cada cambio

| Cambio | Reiniciar |
|---|---|
| Java bajo `src/main/java` | Backend (`docker compose restart backend`) |
| Migracion en `src/main/resources/db/migration` | Backend **y** limpiar el volumen de Postgres si la migracion ya corrio: `docker compose down -v` |
| `application.properties` | Backend |
| `docker-compose.yml` o `Dockerfile` | `docker compose up -d --build` |
| `frontend/src` | Frontend |

Cambiar una migracion ya aplicada **no funciona**: crea `V2__...`.

---

## Como verificar antes de entregar un cambio

```bash
cd backend && mvn -B test        # unitarios, sin Docker
cd backend && mvn -B verify      # incluye Testcontainers (necesita Docker)
```

Tests esperados en verde antes de considerar una fase terminada.

## Verificacion de APIs de Spring AI

La version de Spring AI es **1.1.8**. No inventes clases ni metodos. Si no estas
seguro de una firma, compruébala:

```bash
# Ver el contenido del jar en la cache de Maven
jar tf ~/.m2/repository/org/springframework/ai/spring-ai-model/1.1.8/spring-ai-model-1.1.8.jar

# Ver la firma exacta de una clase (Windows: ruta completa al JDK)
javap -cp <ruta-al-jar> org.springframework.ai.chat.model.ChatModel
```

Nota de la version 1.1.8: `OllamaChatOptions.Builder` usa `numPredict(Integer)`,
no `maxTokens`, para limitar la generacion.

---

## Estado del proyecto

| Fase | Alcance | Estado |
|---|---|---|
| 1 | Base, config por rol, wrapper LLM, esquema, health, CI | Completada |
| 2 | `KnowledgeSearchPort`, ingesta pgvector, corpus, adaptador LocalRAG | Completada |
| 3 | `ToolRegistry` y tools | Pendiente |
| 4 | Research Manager, estado, plan | Pendiente |
| 5 | Research Agent y tool calling | Pendiente |
| 6 | Verificacion de dos capas, iteracion, presupuestos | Pendiente |
| 7 | Sintesis, informe, Reviewer | Pendiente |
| 8 | Checkpoints, reanudacion, cancelacion, SSE | Pendiente |
| 9 | Frontend React | Pendiente |
| 10 | Evaluacion y metricas | Pendiente |
| 11 | Pulido final, README con resultados reales | Pendiente |

## Notas por modulo

### `knowledge/` (Fase 2)

- El agente depende **solo** de `KnowledgeSearchPort`. Nunca de un adaptador.
- Anadir una tercera implementacion es crear un adaptador y registrarlo en
  `KnowledgeConfig`; no se toca el agente.
- `TextChunker` mantiene solape a proposito: sin el, una cita que cae en el
  limite entre fragmentos no apareceria completa en ninguno y la verificacion
  determinista de la Fase 6 la rechazaria.
- `DocumentChunkRepository` usa `JdbcTemplate` y SQL explicito, no el VectorStore
  de Spring AI. La distancia coseno y el limite deben quedar visibles.
- La migracion `V1` fija `vector(768)`, la dimension de `nomic-embed-text`. Cambiar
  de modelo de embeddings exige una migracion nueva, no editar V1.
- **LocalRAG no expone `GET /api/search`.** El adaptador esta verificado contra un
  servidor HTTP simulado (`FakeLocalRagServer`). No inventes endpoints ni degrades
  a `POST /api/chat`, que invoca el LLM. **No modifiques LocalRAG.**

### `llm/` (Fase 1)

- El unico punto de contacto con Spring AI es `SpringAiLlmGateway`.
- Los agentes reciben `LlmResult`, nunca `ChatResponse`.
- `tokensEstimated=true` significa que el proveedor no midio el uso. No lo
  trates como un cero real.

