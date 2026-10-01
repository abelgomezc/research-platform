package com.abegomez.research.research;

/**
 * Estados posibles de una investigacion.
 *
 * <p>El enum es la unica fuente de verdad sobre el flujo. La tabla
 * {@code investigaciones} tiene un {@code CHECK} con los mismos valores: si se
 * anade un estado aqui, hay que crear una migracion nueva, nunca editar V1.
 */
public enum ResearchState {

    CREATED,
    PLANNING,
    RESEARCHING,
    VERIFYING,
    SYNTHESIZING,
    REVIEWING,

    COMPLETED,
    INTERRUPTED,
    FAILED,
    CANCELLED;

    /**
     * Estados terminales: no aceptan ninguna transicion.
     *
     * <p>{@code INTERRUPTED} queda deliberadamente fuera. La investigacion se
     * detenerse y reanudarse desde su checkpoint. Si fuera terminal, el
     * repositorio marcaria {@code finalizado_en} en el momento de la
     * interrupcion y la fecha de fin no seria la real.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * Estados en los que el proceso puede detenerse y reanudarse.
     */
    public boolean isResumable() {
        return this == INTERRUPTED;
    }
}
