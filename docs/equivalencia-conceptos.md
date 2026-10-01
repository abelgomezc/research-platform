# Equivalencia de conceptos: esta plataforma y LangGraph

Este documento existe para dos cosas:

1. Dejar claro que la maquina de estados de `ResearchOrchestrator` (Fase 4) esta
   disenada para ser portable a un framework de grafos.
2. Poder explicar en una entrevista el paralelismo entre **nodo**, **arista**,
   **estado**, **checkpoint** y **human-in-the-loop** en el mundo de agentes.

## Los cinco conceptos

| Concepto | Que es en un framework de grafos | Como se implementa aqui |
|---|---|---|
| **Estado** | Objeto compartido que fluye por el grafo y se persiste | Agregado `Investigacion` en PostgreSQL, con `configuracion_json` para modelos y versiones de prompt |
| **Nodo** | Paso que recibe estado y devuelve estado nuevo | Metodo de `ResearchOrchestrator` con entrada y salida explicitas |
| **Arista** | Transicion condicional entre nodos | `switch` explicito sobre `ResearchStatus`, con las condiciones de limite a la vista |
| **Checkpoint** | Instantanea del estado tras un nodo, para reanudar | Persistencia tras cada paso del orquestador; pasos idempotentes por tarea e intento |
| **Human-in-the-loop** | Pausa que espera intervencion humana antes de seguir | `INTERRUPTED` + `POST /api/research/{id}/resume`; el ciclo completo se automatiza, pero el punto de entrada existe |

## Por que se puede portar

La razon de que el grafo sea portable no es el diagrama: es **que nada depende de
la conversacion para saber donde va el flujo**.

El error tipico al construir agentes es guardar el estado en el historial de
mensajes. Entonces " reanudar" significa volver a enviarle todo al modelo y
preguntarle donde ibamos. Eso no es un checkpoint, es una suposicion.

Aqui el estado esta en tablas:

- La ronda actual esta en `investigaciones.ronda_actual`.
- Las tareas y su estado estan en `tareas_investigacion`.
- Los pasos ya completados son filas con estado `COMPLETADA`, no un mensaje que
  alguien recuerda.
- Los tokens gastados estan en `tokens_consumidos`.

Un checkpoint de LangGraph es, en esencia, exactamente eso: una fila con el estado
del nodo mas un puntero al siguiente nodo. La diferencia es que LangGraph lo
gestiona por ti y aqui lo gestiona el codigo.

## El grafo

```
                        ┌──────────────┐
                        │   PLANNING   │  Research Manager genera el plan
                        └──────┬───────┘
                               ▼
                        ┌──────────────┐
          ┌────────────▶│ RESEARCHING  │◀────┐
          │             └──────┬───────┘     │
          │                    ▼             │
          │             ┌──────────────┐     │  faltan evidencias
          │             │  VERIFYING   │─────┘  y hay ronda/presupuesto
          │             └──────┬───────┘
          │                    ▼             (o limite alcanzado)
          │             ┌──────────────┐
          │             │ SYNTHESIZING │  solo evidencias verificadas
          │             └──────┬───────┘
          │                    ▼
          │             ┌──────────────┐
          │             │   REVIEWING  │────┐
          │             └──────┬───────┘    │ problemas y quedan rondas
          │                    │            │
          │                    ▼            └────▶ REVIEWING
          │             ┌──────────────┐
          │             │  COMPLETED   │  con limitaciones declaradas
          │             └──────────────┘
          ▼
   ┌─────────────┐
   │ INTERRUPTED │──▶ resume desde el ultimo checkpoint
   └─────────────┘
```

Las aristas que vuelven atras son las que hacen que esto sea una **investigacion
iterativa** y no un pipeline lineal. El `VERIFYING` decide si hay que volver a
`RESEARCHING`, y esa decision la toma el codigo leyendo el estado y los limites, no
el modelo.

## Diferencia deonden

En un framework de grafos el flujo suele controlarlo el runtime: el motor decide
que nodo ejecutar, gestiona el checkpoint, aplica limites de pasos. Aqui el flujo
lo controla el codigo de forma explicita.

La razon es que aqui los limites no son un numero de pasos generico sino limites
de negocio heterogeneos: rondas de investigacion, llamadas a tools por tarea,
presupuesto de tokens, tiempo, rondas de revision. Expresarlos como codigo dentro
de una maquina de estados los hace verificables con tests unitarios de transicion,
que es la prueba que se puede demostrar en una entrevista.

## Nota de las versiones

Los identificadores del codigo estan en ingles porque los identificadores tecnicos
(estado, veredicto, tool name) son los que comparan con las APIs de frameworks y
con la documentacion tecnica en ingles. Los textos visibles van en espanol.
