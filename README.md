# AI Research & Intelligence Platform

Plataforma de investigacion agentica: recibe una pregunta de investigacion compleja
y produce un informe donde **cada afirmacion cita su evidencia verificada**.

No es un chatbot. Es un flujo de trabajo con maquina de estados explicita,
agentes con roles, herramientas registradas, presupuestos y verificacion.

> **Estado: FASES 1 a 10 completadas, 199 tests en verde.** El flujo completo esta
> implementado: maquina de estados, plan, agente investigador con tools, verificacion
> en dos capas, sintesis del informe, Reviewer, checkpoints, cancelacion, stream SSE,
> frontend React, dataset de evaluacion y metricas.
>
> **La Fase 11 no esta hecha y no se puede cerrar sin ejecutar el sistema**: no hay
> ninguna cifra real de calidad de informe, coste por investigacion ni tasa de
> verificacion. La seccion *Resultados* mas abajo lo dice explicitamente en lugar de
> inventar numeros. Para cerrarla hace falta levantar Docker y correr el dataset.

## Arquitectura

```
┌────────────┐   HTTP/SSE   ┌──────────────────────────────────────┐
│  frontend  │──────────────▶│        Spring Boot (monolito)      │
│  React 18  │◀──────────────│                                      │
└────────────┘               │  research/  maquina de estados      │
                             │  planning/  Research Manager         │
                             │  agents/    research, verificacion  │
                             │  tools/     registro y ejecucion     │
                             │  knowledge/ puerto + adaptadores    │
                             │  evidence/  fuentes y verificacion  │
                             │  report/    informe con citas       │
                             │  llm/       modelos por rol         │
                             │  evaluation/ dataset y metricas     │
                             └───┬───────────┬──────────┬───────────┘
                                 │           │          │
                          ┌──────▼─────┐ ┌───▼────┐ ┌───▼────────┐
                          │ PostgreSQL │ │ Ollama │ │  SearXNG   │
                          │   +pgvector│ │ local  │ │ (busqueda) │
                          └────────────┘ └────────┘ └────────────┘
```

## Requisitos

- Docker Desktop (Windows/Mac) o Docker Engine + Compose v2 (Linux)
- Java 21 y Maven 3.9+ para desarrollo fuera de Docker
- Al menos 8 GB de RAM disponibles para Ollama

## Como ejecutar

```bash
cp .env.example .env       # Linux/macOS
# copy .env.example .env   # Windows

./arranque-local.sh        # Linux/macOS
arranque-local.bat         # Windows
```

Un solo comando levanta PostgreSQL con pgvector, SearXNG, Ollama, backend y frontend.
Las migraciones de Flyway se aplican automaticamente al arrancar el backend.

| Servicio | URL |
|---|---|
| Frontend | http://localhost:5174 |
| API | http://localhost:8081 |
| OpenAPI (Swagger) | http://localhost:8081/swagger-ui.html |
| Health | http://localhost:8081/actuator/health |
| Metricas | http://localhost:8081/actuator/metrics |

### Desarrollo fuera de Docker

```bash
cd backend
mvn spring-boot:run
```

Necesitas un PostgreSQL 16 con pgvector y un Ollama con los modelos descargados:

```bash
docker compose up -d postgres searxng
docker compose --profile with-ollama up -d ollama
ollama pull qwen3:8b
ollama pull nomic-embed-text
```

## Comandos utiles

```bash
# Tests unitarios (sin Docker)
cd backend && mvn test

# Tests de integracion con Testcontainers (requiere Docker)
cd backend && mvn verify

# Empaquetar
cd backend && mvn -DskipTests package

# Ver logs
docker compose logs -f backend

# Detener todo
docker compose --profile with-ollama down
```

## Configuracion

Toda la configuracion esta en `backend/src/main/resources/application.properties`.
Los secretos vienen de variables de entorno; el repositorio no contiene ninguno.

### Modelos por rol de agente

El modelo se elige por rol, sin tocar codigo. Cambiar de modelo es cambiar
propiedades:

```properties
app.llm.models.planner=qwen3:8b
app.llm.models.researcher=qwen3:8b
app.llm.models.verifier=qwen3:8b
app.llm.models.synthesizer=qwen3:8b
app.llm.models.reviewer=qwen3:8b
app.llm.embedding-model=nomic-embed-text
```

Tambien por variable de entorno: `LLM_MODEL_PLANNER`, `LLM_MODEL_RESEARCHER`, etc.

**Requisito:** el modelo de chat debe soportar tool calling en Ollama. `qwen3:8b`
lo soporta.

### Adaptador de LocalRAG (opcional)

El conocimiento interno tiene dos implementaciones intercambiables, seleccionadas
por `app.knowledge.provider`. **El agente no sabe cuál está activa**: depende de la
interfaz `KnowledgeSearchPort`, no de una implementación.

- `knowledge.provider=pgvector` (por defecto, incluido en este repositorio)
- `knowledge.provider=localrag` (adaptador HTTP)

Para activarlo:

```properties
app.knowledge.provider=localrag
app.knowledge.localrag.base-url=http://localhost:8080
app.knowledge.localrag.search-path=/api/search
```

```bash
KNOWLEDGE_PROVIDER=localrag LOCALRAG_URL=http://host.docker.internal:8080 docker compose up -d
```

#### Estado real del adaptador: verificado, no operativo

El adaptador está **implementado y verificado contra el contrato**, pero
**LocalRAG todavía no expone el endpoint que necesita**. Por eso falla de forma
controlada en lugar de fingir que funciona.

**Contrato esperado** (documentado en `LocalRagSearchItem`):

```
GET /api/search?query=...&topK=...
```

Devuelve una lista de:

```json
[
  {"documentId": "doc-1", "fileName": "03-machine-learning.md", "pageNumber": null,
   "chunkNumber": 2, "text": "El modelo cubre patrones que las reglas no catalogan.",
   "score": 0.91}
]
```

Solo recuperación: **sin LLM, sin reescritura de consulta, sin historial.**

**Por qué no funciona todavía.** Revisando LocalRAG sin modificarlo, sus únicos
endpoints son:

| Endpoint | Por qué no sirve |
|---|---|
| `POST /api/chat` | Invoca el LLM y genera respuesta. No devuelve texto de fragmento ni puntaje. |
| `GET /api/documents/{id}/content` | Devuelve el documento completo, sin fragmentar y sin puntaje. |
| `GET /api/health` | Solo salud. |

El método de búsqueda de LocalRAG (`RagQueryService.hybridSearch`) es **privado** y
está acoplado a `ask()`, que antes de buscar reescribe la consulta con el LLM,
evalúa calidad CRAG, genera con Self-RAG y guarda historial. No es recuperación pura.

**Consecuencia práctica:** sin texto de fragmento el agente no puede extraer la
cita textual, que es el núcleo de este proyecto. Por eso el puerto exige ambos.

**Al activar el proveedor:** el health indicator queda en `DOWN` con el motivo
explícito, y cada búsqueda lanza un error controlado que la tool convertirá en un
mensaje entendible para el agente. **El proveedor por defecto sigue siendo
pgvector.**

> El adaptador está probado contra un **servidor HTTP simulado** que reproduce el
> contrato (`FakeLocalRagServer`), no contra LocalRAG real, porque el endpoint no
> existe. La clase de prueba seguirá siendo válida sin cambios cuando LocalRAG lo
> exponga.

### Puertos y convivencia con LocalRAG

Para que ambos proyectos convivan en la misma máquina:

| Servicio | Este proyecto | LocalRAG |
|---|---|---|
| Backend | 8081 | 8080 |
| Frontend | 5174 | 5173 |
| PostgreSQL (host) | 5433 | 5432 |
| SearXNG (host) | 8090 | — |
| Ollama | 11434 (compartido) | 11434 (compartido) |

## API de investigaciones

| Endpoint | Que hace |
|---|---|
| `POST /api/research` | Crea una investigacion y la lanza en segundo plano. Responde `202` con el id. |
| `GET /api/research` | Listado paginado. |
| `GET /api/research/{id}` | Detalle: estado, ronda, presupuesto y resumen de verificacion. |
| `GET /api/research/{id}/stream` | Stream SSE del progreso. Acepta `Last-Event-ID`. |
| `GET /api/research/{id}/events` | Eventos discretos, sin abrir stream. |
| `POST /api/research/{id}/cancel` | Cancela. Cooperativo: responde `202`. |
| `POST /api/research/{id}/checkpoint` | Guarda el estado actual. |
| `POST /api/research/{id}/resume` | Reanuda una investigacion interrumpida. |
| `GET /api/research/{id}/report` | Ultimo informe, o `?historico=true` para todas las versiones. |
| `GET /api/research/{id}/metrics` | Metricas calculadas de lo que ocurrio. |
| `GET /api/evaluation/summary` | Comparativa entre investigaciones. |
| `POST /api/evaluation/run` | Ejecuta el dataset. Lento: cada caso es una investigacion completa. |

La ejecucion es asincrona a proposito. Una investigacion consume minutos y varias
llamadas al modelo; si la peticion HTTP esperara, un corte de red perderia el
trabajo. Se devuelve un id y el progreso se sigue por SSE.

El stream usa `EventSource` en el navegador porque reconecta solo y reenvia
`Last-Event-ID`. Los eventos estan persistidos en `eventos_investigacion`, asi que
un corte de red no pierde progreso: se reenvia lo que se emitio mientras el
cliente no estaba.

## Resultados

**No hay cifras de calidad todavia.** No se han ejecutado investigaciones reales
contra este backend, y por tanto no existe ninguna medida de:

- cobertura de criterios del dataset de evaluacion,
- tasa de citas verificadas por informe,
- tokens consumidos por investigacion,
- distribucion de uso y fallos por herramienta.

Cualquier numero en este documento seria inventado. Los que faltan se obtienen
asi:

```bash
# 1. Levantar la infraestructura y el backend
docker compose up -d --build

# 2. Ejecutar el dataset de evaluacion (5 casos, cada uno una investigacion)
curl -X POST "http://localhost:8081/api/evaluation/run" \
     -H "Content-Type: application/json"

# 3. Comparativa de lo obtenido
curl "http://localhost:8081/api/evaluation/summary"
```

El dataset esta en `backend/src/main/resources/evaluation/dataset.json` y cada
caso declara las afirmaciones que el informe debe cubrir. La puntuacion es
cobertura de esos criterios, no parecido textual: dos redacciones del mismo hecho
son ambas correctas.

**Lo que si esta medido:** 199 tests unitarios en verde, incluidos los que
comprueban los guards de seguridad, la maquina de estados, el presupuesto, el
protocolo de tool calling, las dos capas de verificacion y las reglas de citacion
del informe.

## Documentacion

| Documento | Contenido |
|---|---|
| [docs/arquitectura.md](docs/arquitectura.md) | Modulos, flujo y frontier de cada capa |
| [docs/decisiones.md](docs/decisiones.md) | Decisiones de arquitectura y por que |
| [docs/esquema-base-datos.md](docs/esquema-base-datos.md) | Esquema, indices y diagrama Mermaid |
| [docs/equivalencia-conceptos.md](docs/equivalencia-conceptos.md) | Nodo, arista, checkpoint y LangGraph |
| [AGENTS.md](AGENTS.md) | Reglas para asistentes de IA |
