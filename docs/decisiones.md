# Decisiones de arquitectura

Registro de decisiones y su motivo. El formato es breve a proposito: la
justificacion vive aqui, el detalle en el codigo.

---

## D1. Maquina de estados explicita en codigo, no gobernada por el LLM

**Decision.** `ResearchOrchestrator` (Fase 4) implementa las transiciones como
codigo con nodos y aristas explicitas. El LLM produce contenido, nunca la
transicion siguiente.

**Por que.** Tres razones concretas:

1. **Presupuestos garantizados.** Si el modelo decide cuando parar, no hay forma de
   garantizar que respete el maximo de rondas o el presupuesto de tokens. Con
   transiciones en codigo, el limite es una condicion del `switch`.
2. **Reanudacion.** Sin transiciones en codigo, reconstruir "donde estaba" tras una
   caida exige adivinar. Con estados explicitos, reanudar es leer el estado y seguir.
3. **Explicabilidad en entrevista.** El grafo de flujo se lee y se depura. Un prompt
   que decide el flujo no.

**Alternativa descartada.** Dejar que un agente "decida el siguiente paso" con un
 planner prompt. Se descarto por las dos primeras razones.

**Consecuencia.** Añadir un paso es modificar codigo, no un prompt. A cambio, el
comportamiento es predecible y testeable.

---

## D2. Verificacion de evidencia en dos capas

**Decision.** La verificacion tiene una capa determinista por codigo (la cita
existe literalmente en el texto de la fuente) y una capa por LLM (la fuente
respalda la afirmacion). La determinista es bloqueante: si la cita no existe, la
evidencia no se usa.

**Por que.** Un LLM puede afirmar que una cita existe con total confianza y estar
equivocado. Una comparacion normalizada de texto no. La capa determinista es la
garantia fuerte; la del LLM aporta el juicio semantico que el codigo no puede dar.

Invertir el orden (primero LLM, luego codigo) desperdicia una llamada al modelo en
evidencias que el codigo ya iba a rechazar.

---

## D3. Puerto de conocimiento con dos adaptadores

**Decision.** `KnowledgeSearchPort` define el contrato de busqueda de conocimiento
interno. Hay dos implementaciones: pgvector (incluida, por defecto) y LocalRAG
(opcional, por HTTP). El agente y la tool `search_knowledge_base` no conocen cual
esta activa.

**Por que.** Permite comparar dos estrategias de busqueda sobre el mismo corpus sin
tocar el agente, y cumple el objetivo del proyecto de no reconstruir un RAG. El
puerto tambien es lo que hace testeable el agente sin Levantar un indice vectorial.

**Consecuencia.** El contrato debe ser estrecho: consulta, topK y resultados con
texto, documento, referencia y puntaje. LocalRAG debe exponer un endpoint que solo
busque y devuelva fragmentos, sin generar respuesta con el LLM.

**Estado verificado (Fase 2).** Revisado el codigo de LocalRAG sin modificarlo: **el
endpoint no existe**. Solo hay POST /api/chat (invoca el LLM, no devuelve texto de
fragmento ni puntaje) y GET /api/documents/{id}/content (documento completo, sin
fragmentar). El metodo hybridSearch es privado y esta acoplado a sk(), que ademas
reescribe la consulta con el LLM y guarda historial.

Por eso el adaptador se implementa contra el contrato documentado y falla de forma
controlada con un mensaje explicito. Se verifico contra un servidor HTTP simulado, no
contra LocalRAG real. La alternativa de degradar a /api/chat se descarto: generaria
tokens desde dos proyectos a la vez y, sin texto de fragmento, el agente no podria
extraer la cita textual que sostiene todo el sistema.

---

## D4. Limites como parte del diseno, no como validacion posterior

**Decision.** Maximo de rondas, llamadas por tarea, presupuesto de tokens, tiempo
maximo y rondas de revision son parametros del flujo verificados en cada
transicion. Al alcanzar un limite, el sistema **termina con lo que tiene** y lo
declara en la seccion de limitaciones del informe.

**Por que.** Una investigacion que falla al agotar su presupuesto no entrega nada.
Cerrar de forma controlada con hallazgos parciales y limitaciones declaradas siempre
entrega algo honesto. Es la diferencia entre un sistema usable y uno que se rompe
en el caso dificil, que es justo el caso que mas importa.

---

## D5. Contar tokens con la metadata del proveedor y estimar cuando no exista

**Decision.** Se usa `Usage` de la respuesta cuando el proveedor la entrega. Cuando
viene `EmptyUsage` (0,0), que es lo que devuelven los proveedores que no miden el
uso, se estima con cuatro caracteres por token y se marca `tokensEstimated=true`.

**Por que.** Reportar cero silenciosamente haria que el presupuesto de tokens
pareciera-eterno y que las metricas de coste fueran falsas. Marcar la estimacion
permite distinguir en la evaluacion entre coste medido y coste estimado.

**Limitacion asumida.** La heuristica de cuatro caracteres por token es aproximada
y depende del tokenizador. Queda documentada como tal, no escondida.

---

## D6. Spring AI detras de interfaces propias

**Decision.** Los agentes dependen de `LlmGateway`, no de `ChatClient` ni de
Spring AI. La implementacion `SpringAiLlmGateway` es el unico punto que conoce el
framework.

**Por que.** Permite probar los agentes con un modelo simulado y determinista
(sin Ollama) y concentra en un solo lugar lo que los agentes no deben resolver por
su cuenta: elegir modelo, contar tokens, medir duracion y reintentar.

**Consecuencia.** Añadir una capacidad de Spring AI (tool calling nativo,
structured output) se hace en el wrapper, no en cada agente.

---

## D7. Un modelo de chat por rol de agente

**Decision.** Cada rol (planner, researcher, verifier, synthesizer, reviewer) tiene
su nombre de modelo configurable. `LlmModelRegistry` construye un `ChatModel` por
rol sobre la misma instancia de `OllamaApi`.

**Por que.** Un verificador necesita temperatura baja y un sintetizador necesita
contexto grande. Compartir un unico modelo global obliga a ceder en alguno de los dos.
Tambien permite comparar roles en la evaluacion cambiando una propiedad.

---

## D8. application.properties, no YAML

**Decision.** Configuracion en `application.properties` con variables de entorno.

**Por que.** Requisito explicito del proyecto. Ademas, las rutas de Flyway y los
patrones de log se leen mejor en este formato que en YAML anidado.

---

## D9. Las tools se definen por esquema, permisos y riesgo desde el inicio

**Decision.** `ToolRegistry` (Fase 3) registra cada tool con `name`, `description`,
`inputSchema`, `outputSchema`, `permissions`, `riskLevel`, `timeout` y `reintentos`,
aunque en este proyecto no se implemente MCP.

**Por que.** Preparar la frontera ahora evita reescribir el registro al anadir MCP en
un proyecto posterior. Un fallo de tool nunca tumba la investigacion: la tarea
registra el fallo y el agente continua con otras fuentes.

---

## D10. Sin sobreingenieria en seguridad

**Decision.** Token de API configurable por variable de entorno, CORS restringido al
frontend declarado, SSRF y SQL de solo lectura en las tools, validacion de archivos
subidos. Sin Spring Security completo, sin RBAC, sin multi-tenancy.

**Por que.** El enunciado reserva la seguridad avanzada para un proyecto posterior.
Las tools de este proyecto son de bajo riesgo: lectura o escritura interna. Anadir un
modelo de permisos completo aqui seria trabajo que no se demuestra con el objetivo de
este proyecto.
