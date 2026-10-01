package com.abegomez.research.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Implementacion de una herramienta.
 *
 * <p>Cada agente recibe solo las tools que le corresponden. Una tool no decide
 * nada del flujo: ejecuta, devuelve o falla de forma controlada.
 */
public interface ResearchTool {

    /**
     * Ficha de la herramienta: nombre, esquemas, permisos, riesgo, timeout y
     * reintentos. Es lo que el agente lee para decidir como usarla.
     */
    ToolDefinition definition();

    /**
     * Ejecuta la herramienta.
     *
     * <p>Debe devolver un {@link ToolResult}. Las excepciones se capturan en el
     * ejecutor, no aqui.
     */
    ToolResult execute(ToolContext context, JsonNode parametros);
}