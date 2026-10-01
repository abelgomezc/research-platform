# AI Research & Intelligence Platform

Plataforma de investigacion agentica: recibe una pregunta de investigacion compleja
y produce un informe donde **cada afirmacion cita su evidencia verificada**.

No es un chatbot. Es un flujo de trabajo con maquina de estados explicita,
agentes con roles, herramientas registradas, presupuestos y verificacion.

> **Estado: FASE 1 completada.** Base del proyecto, configuracion por rol de agente,
> wrapper del LLM con conteo de tokens y reintentos, esquema PostgreSQL + pgvector,
> health checks y CI. Las fases siguientes anaden el flujo de investigacion.

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
| Frontend | http://localhost:5173 |
| API | http://localhost:8080 |
| OpenAPI (Swagger) | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| Metricas | http://localhost:8080/actuator/metrics |

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

### Adaptador de LocalRAG (opcional, Fase 2)

El conocimiento interno tiene dos implementaciones intercambiables Selected por
`knowledge.provider`. El agente no sabe cual esta activa.

- `knowledge.provider=pgvector` (por defecto, incluido en este repositorio)
- `knowledge.provider=localrag` (adaptador HTTP, se implementa en la Fase 2)

Las instrucciones de activacion se documentaran al cerrar la Fase 2.

## Documentacion

| Documento | Contenido |
|---|---|
| [docs/arquitectura.md](docs/arquitectura.md) | Modulos, flujo y frontier de cada capa |
| [docs/decisiones.md](docs/decisiones.md) | Decisiones de arquitectura y por que |
| [docs/esquema-base-datos.md](docs/esquema-base-datos.md) | Esquema, indices y diagrama Mermaid |
| [docs/equivalencia-conceptos.md](docs/equivalencia-conceptos.md) | Nodo, arista, checkpoint y LangGraph |
| [AGENTS.md](AGENTS.md) | Reglas para asistentes de IA |
