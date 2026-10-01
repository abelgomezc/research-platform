package com.abegomez.research.tools;

/**
 * Permiso requerido por una herramienta.
 *
 * <p>Se declara aunque en este proyecto todas las tools sean de bajo riesgo: es
 * lo que permite anadir despues un enforcement real sin cambiar el registro.
 */
public enum ToolPermission {

    /** Solo lee. No modifica nada. */
    READ,

    /** Escribe dentro del sistema: evidencia, tareas, notas. */
    WRITE_INTERNAL
}