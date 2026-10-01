package com.abegomez.research.research;

/**
 * Tipos de evento de la linea de tiempo de una investigacion.
 *
 * <p>El nombre se persiste en {@code eventos_investigacion.tipo} como texto.
 * Anadir un valor aqui no requiere migracion porque la columna no tiene
 * {@code CHECK}: es deliberado, para que el historial de evento sobreviva a un
 * despliegue que retire un tipo de evento.
 *
 * <p>No se renombran valores existentes. El historial guardado deja de
 * interpretarse si se cambia un nombre.
 */
public enum ResearchEventType {

    /** La investigacion se creo y esta lista para planificar. */
    INVESTIGATION_CREATED,

    /** Cambio de estado de la maquina de estados. */
    STATE_CHANGED,

    /** El Planner genero el plan inicial. */
    PLAN_CREATED,

    /** Una tarea paso a EN_CURSO. */
    TASK_STARTED,

    /** Una tarea se completo. */
    TASK_COMPLETED,

    /** Una tarea se descarto. */
    TASK_DISCARDED,

    /** Una tarea fallo de forma irrecuperable. */
    TASK_FAILED,

    /** Se registro una evidencia, todavia sin verificar. */
    EVIDENCE_SAVED,

    /** El Verifier resolvio el veredicto de una evidencia. */
    EVIDENCE_VERIFIED,

    /** Se detecto una contradiccion entre evidencias. */
    CONTRADICTION_DETECTED,

    /** Se genero una version del informe. */
    REPORT_GENERATED,

    /** El Reviewer devolvio su revision. */
    REVIEW_COMPLETED,

    /** El presupuesto de tokens bajo del margen de seguridad. */
    BUDGET_LOW,

    /** Se agotaron las rondas disponibles. */
    ROUNDS_EXHAUSTED,

    /** Empieza una ronda nueva de investigacion. */
    ROUND_STARTED,

    /** Se genero un checkpoint para poder reanudar. */
    CHECKPOINT_SAVED,

    /** La investigacion se reanudo desde un checkpoint. */
    CHECKPOINT_RESUMED,

    /** La investigacion se cancelo a peticion del usuario. */
    CANCELLATION_REQUESTED,

    /** La investigacion se detuvo por un fallo y se guardo su motivo. */
    FAILURE
}
