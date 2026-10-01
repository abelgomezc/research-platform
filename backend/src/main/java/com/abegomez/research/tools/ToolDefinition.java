package com.abegomez.research.tools;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Ficha de una herramienta: lo que el agente ve y lo que el ejecutor impone.
 *
 * <p>Esta estructura existe para que el registro sea autocontenido. Cuando se
 * implemente MCP en un proyecto posterior, esta ficha se convierte en la
 * declaracion de la tool sin cambiar los agentes.
 *
 * @param name          nombre unico, tal como lo invoca el agente
 * @param description   que hace y cuando usarla; es lo que lee el modelo
 * @param inputSchema   JSON Schema de los parametros de entrada
 * @param outputSchema  JSON Schema de lo que devuelve
 * @param permissions   permisos requeridos
 * @param riskLevel     nivel de riesgo
 * @param timeout       tiempo maximo de ejecucion
 * @param retries       reintentos adicionales tras un fallo
 */
public record ToolDefinition(
        String name,
        String description,
        String inputSchema,
        String outputSchema,
        ToolPermission permission,
        RiskLevel riskLevel,
        Duration timeout,
        int retries) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(timeout, "timeout");

        if (name.isBlank()) {
            throw new IllegalArgumentException("El nombre de la tool no puede estar vacio");
        }
        if (!name.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "El nombre de la tool debe seguir el patron snake_case en minusculas: " + name);
        }
        if (retries < 0) {
            throw new IllegalArgumentException("Los reintentos no pueden ser negativos: " + retries);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("El timeout debe ser positivo: " + timeout);
        }
    }

    /**
     * compacta la ficha para el log, sin volcar los esquemas JSON enteros.
     */
    @Override
    public String toString() {
        return name + "(" + permission + "/" + riskLevel + ")";
    }

    /**
     * Verifica que los esquemas son JSON valido. Se usa en el arranque y en los
     * tests: un esquema mal formado haria fallar la llamada al modelo mas tarde,
     * con un error dificil de interpretar.
     */
    public void validateSchemas(ObjectMapper objectMapper) {
        parseSchema(objectMapper, inputSchema, name + ".inputSchema");
        parseSchema(objectMapper, outputSchema, name + ".outputSchema");
    }

    private static void parseSchema(ObjectMapper objectMapper, String schema, String campo) {
        try {
            objectMapper.readTree(schema);
        }
        catch (Exception ex) {
            throw new IllegalStateException(campo + " no es JSON valido: " + ex.getMessage(), ex);
        }
    }

    /**
     * Helpers para construir las fichas sin repetir las validaciones.
     */
    public static final class Builder {

        private final String name;
        private final String description;
        private final String inputSchema;
        private final String outputSchema;
        private ToolPermission permission = ToolPermission.READ;
        private RiskLevel riskLevel = RiskLevel.LOW;
        private Duration timeout = Duration.ofSeconds(30);
        private int retries = 1;

        public Builder(String name, String description, String inputSchema, String outputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.outputSchema = outputSchema;
        }

        public Builder permission(ToolPermission value) {
            this.permission = value;
            return this;
        }

        public Builder riskLevel(RiskLevel value) {
            this.riskLevel = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder retries(int value) {
            this.retries = value;
            return this;
        }

        public ToolDefinition build() {
            return new ToolDefinition(name, description, inputSchema, outputSchema,
                    permission, riskLevel, timeout, retries);
        }
    }

    /**
     * Nombres de las tools del proyecto. Se centralizan para que un error de
     * escritura no se descubra en produccion.
     */
    public static final class Names {

        public static final String SEARCH_KNOWLEDGE_BASE = "search_knowledge_base";
        public static final String SEARCH_DOCUMENTS = "search_documents";
        public static final String READ_DOCUMENT = "read_document";
        public static final String SEARCH_WEB = "search_web";
        public static final String FETCH_PAGE = "fetch_page";
        public static final String QUERY_DATABASE = "query_database";
        public static final String SAVE_EVIDENCE = "save_evidence";
        public static final String GET_PREVIOUS_RESEARCH = "get_previous_research";
        public static final String CREATE_RESEARCH_TASK = "create_research_task";
        public static final String MARK_TASK_COMPLETE = "mark_task_complete";

        private Names() {
        }

        public static List<String> all() {
            return List.of(SEARCH_KNOWLEDGE_BASE, SEARCH_DOCUMENTS, READ_DOCUMENT, SEARCH_WEB,
                    FETCH_PAGE, QUERY_DATABASE, SAVE_EVIDENCE, GET_PREVIOUS_RESEARCH,
                    CREATE_RESEARCH_TASK, MARK_TASK_COMPLETE);
        }
    }
}