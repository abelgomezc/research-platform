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
- **Pendiente:** el directorio `resources/prompts/` todavia no existe. Los seis
  prompts estan como cadenas dentro de las clases de agente. El versionado si
  existe y se registra (`VERSION_PROMPT`), pero mover el texto a ficheros es
  trabajo pendiente. Al hacerlo, cambia la version: es un cambio de prompt, y el
  versionado existe justo para poder distinguirlo de una correccion de codigo.

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
| 3 | `ToolRegistry` y tools | Completada |
| 4 | Research Manager, estado, plan | Completada |
| 5 | Research Agent y tool calling | Completada |
| 6 | Verificacion de dos capas, iteracion, presupuestos | Completada |
| 7 | Sintesis, informe, Reviewer | Completada |
| 8 | Checkpoints, reanudacion, cancelacion, SSE | Completada |
| 9 | Frontend React | Completada |
| 10 | Evaluacion y metricas | Completada |
| 11 | Pulido final, README con resultados reales | Pendiente de medir |

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

### `tools/` (Fase 3)

- Los permisos por rol se declaran en `ToolPermissionsConfig`, **nunca** en el
  prompt. Si estuvieran en el prompt, un texto externo podria pedirle al modelo
  una tool que no le corresponde y bastaria con que cooperara.
- `save_evidence` inserta **siempre** en `estado_verificacion = 'PENDIENTE'`.
  El agente no verifica su propia evidencia: si pudiera marcarla como
  verificada, la verificacion de la Fase 6 seria una decoracion.
- `query_database` corre con el pool `readOnlyDataSource` (usuario `research_ro`,
  solo `GRANT SELECT`). El validador reduce superficie, no la sustituye. No la
  cambies por el `JdbcTemplate` principal: perderias la garantia de que esa tool
  no escribe.
- `fetch_page` valida la URL **resolviendo DNS y comprobando todas las
  direcciones**. Un host con un A publico y un AAAA a loopback es un bypass real
  de los validadores que solo miran el texto de la URL.
- `mark_task_complete` cierra la tarea de `ToolContext.tareaId()`, no la que el
  modelo indique: el agente no elige que tarea cierra.
- `create_research_task` tiene tope de tareas nuevas por ronda. Sin el, un
  agente podria inflar el plan hasta agotar el presupuesto, que es una decision
  del sistema y no del modelo.
- El texto de `search_web` y `fetch_page` va delimitado como
  `CONTENIDO EXTERNO NO VERIFICADO`. Es la frontera que impide que una pagina
  inyecte instrucciones.
- `ToolResult` con `exitoso=false` **no** es una excepcion: el agente recibe un
  mensaje entendible y decide. Ningun fallo de tool tumba la investigacion.
- `ReadOnlyDataSourceConfig` declara el pool de solo lectura **sin** `@Primary`
  a proposito: el resto de la aplicacion sigue usando el pool con escritura.

### `research/` (Fases 4, 7, 8)

- **El LLM no cambia el estado.** `ResearchManager.transicionar` es el unico
  punto, y relee el estado antes de validar contra `ResearchStateMachine`. Si
  el llamador dijera el estado de origen, la maquina no serviria para nada.
- `INTERRUPTED` **no es terminal** a proposito: una investigacion interrumpida se
  reanuda. Si fuera terminal, el repositorio escribiria `finalizado_en` al
  interrumpirse y la fecha de fin no seria la real.
- El presupuesto se descuenta con `UPDATE ... WHERE tokens_consumidos + ? <=
  presupuesto_tokens`. Es atomico a proposito: leer-modificar-escribir permitiria
  que dos tareas en paralelo gastaran el mismo saldo.
- El margen de seguridad se comprueba **antes** de la llamada, no despues. El
  total de tokens solo se conoce cuando la llamada ya termino.
- Un informe que no supera la revision deja la investigacion en `INTERRUPTED`, no
  en `COMPLETED`. Marcar exito cuando el Reviewer rechazo es el fallo mas grave
  posible del sistema.
- La cancelacion es **cooperativa**: se comprueba entre tareas y entre fases,
  nunca en mitad de una. Interrumpir una tool a medias podria dejar evidencia
  guardada sin cerrar.
- `ResearchEventStreamService` usa **hilos dedicados por stream**, no un hilo por
  evento. Y consulta el estado en `investigaciones`, no los eventos, para saber
  si termino.

### `agents/` (Fases 5)

- **No hay tool calling nativo.** `chatWithTools` delega en `chat`, asi que el
  protocolo es JSON en el texto (`ToolCall`). Es una decision, no una carencia:
  Ollama via Spring AI no expone tool calling fiable, y con un protocolo
  declarado el bucle se prueba con un gateway falso.
- `ToolCall` busca el primer objeto JSON **balanceado** en la respuesta. Exigir
  que la respuesta sea exactamente un JSON hace fallar llamadas que eran validas.
- El bucle tiene tres topes que impone el codigo: pasos maximos, presupuesto por
  paso y cierre obligatorio. Un agente sin cierre perderia su trabajo.
- `save_evidence` no se permite al Verifier: si el verificador pudiera crear su
  propia evidencia, la verificacion seria una decoracion.

### `evidence/` (Fases 6)

- La verificacion es en **dos capas y en este orden**: primero determinista
  (`CitationMatcher`), despues semantica (el Verifier). Al reves, una cita
  inventada pasaria si el modelo se equivoca.
- Una cita que no aparece en la fuente **no llega a consultar al modelo**. El test
  lo comprueba con `verifyNoInteractions`.
- La comprobacion determinista tiene cuatro modos: `EXACTA`, `SIN_ESPACIOS`,
  `INVERTIDA` y `TOLERANTE`. La tolerante compara **por posicion**, no como
  conjunto de caracteres: reordenar las palabras es otra cita.
- Sin veredicto del modelo, una cita confirmada queda `PARCIAL`, nunca
  `VERIFICADA`. Aprobar por defecto convertiria al verificador en una
  formalidad.
- `CitationMatcher.normalizar` es publico porque `evaluation/` necesita la misma
  normalizacion. Si cada modulo normalizara por su cuenta, la metrica dependeria
  de una convencion invisible.

### `report/` (Fase 7)

- `ReportValidator` es la **red de seguridad** del sintetizador. La garantia de
  que el informe cita no puede depender de que el modelo obedezca.
- Solo `VERIFICADA` puede sostener una afirmacion del informe. El sintetizador
  recibe la evidencia ya filtrada para no citar algo que se sostiene mal.
- Declarar un limite **no necesita cita**. Sin esa excepcion, el modelo tendria
  que inventar una fuente para cerrar sus propios huecos.
- `totalCitas` cuenta **referencias**, no marcadores: `[evidencias 1, 2]` son dos
  citas, y para la metrica de evaluacion eso es lo que importa.

### `evaluation/` (Fase 10)

- **Ningun numero se inventa.** Si un dato no se puede calcular se devuelve
  `null`, no `0`. Cero es un valor real con significado distinto.
- La puntuacion es **cobertura de criterios esperados**, no parecido textual. Dos
  redacciones distintas del mismo hecho son ambas correctas.
- Se mide tambien si el criterio aparece **citado**. Un dato sin cita no es una
  respuesta valida en este sistema.
- La media solo cuenta casos **ejecutados**: un caso fallido tiene 0 por
  construccion y mezclarla haria que el numero no describiera nada.
- Un caso sin criterios esperados no se puntua: su 0 no significaria nada.

### `llm/` (Fase 1)

- El unico punto de contacto con Spring AI es `SpringAiLlmGateway`.
- Los agentes reciben `LlmResult`, nunca `ChatResponse`.
- `tokensEstimated=true` significa que el proveedor no midio el uso. No lo
  trates como un cero real.

