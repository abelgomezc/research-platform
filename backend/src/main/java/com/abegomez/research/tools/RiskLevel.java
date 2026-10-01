package com.abegomez.research.tools;

/**
 * Nivel de riesgo de una herramienta.
 *
 * <p>En este proyecto todas las tools son {@link #LOW}: leen datos externos o
 * escriben solo en la propia base de datos. No hay tools con efectos externos
 * peligrosos. El nivel se declara igual porque la frontera se prepara para un
 * proyecto posterior con MCP.
 */
public enum RiskLevel {

    LOW,
    MEDIUM,
    HIGH
}