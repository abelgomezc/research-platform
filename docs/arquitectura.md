# Arquitectura

## Idea central

El LLM decide **contenido**: que plan hacer, que herramienta usar, si una fuente
respalda una afirmacion, como redactar. El codigo controla **el flujo**: que paso
sigue, cuando se detiene, cuanto se puede gastar y que se persiste.

Separar ambas cosas es lo que hace que el sistema sea reanudable, auditable y
limitado. Si el modelo decide las transiciones, no hay garantia de que respete
presupuestos ni de que se pueda reanudar tras una caida.

## Modulos

```
com.abegomez.research
├── research/      investigacion: orquestador, estado, presupuestos, eventos
├── planning/      Research Manager: plan y tareas
├── agents/        Research, Verification, Synthesis, Reviewer
├── tools/         registro de herramientas, definiciones, ejecucion
├── knowledge/     puerto de busqueda + adaptadores + ingesta
├── evidence/      fuentes, evidencias, verificacion, contradicciones
├── report/        generacion y validacion del informe
├── evaluation/    dataset, runner, metricas
├── llm/           modelos por rol, wrapper con medicion y reintentos
├── api/           controllers, DTOs, manejo de errores
└── common/        utilidades, excepciones, configuracion transversal
```

Solo `research/`, `llm/`, `api/` y `common/` existen en la Fase 1. Los demas modulos
se crean en sus fases.

## Capa LLM

El punto de entrada de todo el LLM es `LlmGateway`. Los agentes dependen de la
interfaz, no de Spring AI, lo que permite probarlos con dobles deterministas.

```
Agente
  └─▶ LlmGateway (interfaz)
        └─▶ SpringAiLlmGateway
              ├─ LlmModelRegistry   resuelve el modelo por rol
              ├─ conteo de tokens   metadata del proveedor o estimacion
              ├─ duracion
              └─ reintentos        backoff exponencial acotado
```

`LlmModelRegistry` construye un `ChatModel` por rol a partir de la misma instancia
de `OllamaApi`. No hay un solo modelo global: cada rol puede apuntar a un modelo
distinto cambiando `app.llm.models.<rol>`.

### Conteo de tokens

`LlmResult` distingue dos casos:

- `tokensEstimated=false`: el proveedor entrego `Usage` con valores reales.
- `tokensEstimated=true`: se estimo con la heuristica de cuatro caracteres por token.

La estimacion se activa cuando la metadata viene con `EmptyUsage` (0,0), que es lo
que devuelven los proveedores que no miden el uso. Es una limitacion conocida y
queda registrada en el resultado en lugar de reportar un cero silencioso.

### Reintentos

Politica configurable (`app.llm.retry.*`): numero de intentos, espera inicial,
multiplicador y techo del backoff. Se reintenta ante **cualquier** fallo
infraestructura del proveedor, incluida una respuesta vacia, y el agotamiento de
intentos produce un `LlmCallException` controlado. El agente continua con otras
fuentes; un fallo del modelo nunca tumba la investigacion.

## Base de datos

PostgreSQL 16 con pgvector. Flyway aplica `V1__esquema_base.sql` al arrancar.

El estado de la investigacion vive en tablas, **no en el historial de conversacion**.
Eso es lo que permite reanudar tras una caida sin repetir pasos.

Detalle completo en [esquema-base-datos.md](esquema-base-datos.md).

## Configuracion

- `application.properties` con variables de entorno y valores por defecto de
  desarrollo.
- `@ConfigurationProperties` para `app.llm` y `app.search`.
- Sin secretos: `.env` no se versiona, `.env.example` si.

## Observabilidad

- Logs con MDC: `investigacionId` y `agente` en el patron de log.
- `LlmMetrics` publica duracion (`llm.call.duration`), tokens
  (`llm.tokens.input` / `llm.tokens.output`) y fallos, etiquetados por rol y modelo.
- Health indicators: PostgreSQL (nativo), `ollama`, `searxng`.
- `GlobalExceptionHandler` unifica el formato de error de la API.

El `health` de Ollama no solo comprueba que responda: verifica que los modelos
configurados por rol esten descargados, porque un modelo ausente es el fallo mas
frecuente y mas dificil de diagnosticar.

## Decisiones

Ver [decisiones.md](decisiones.md).
