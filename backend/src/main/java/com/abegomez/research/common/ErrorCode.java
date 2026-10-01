package com.abegomez.research.common;

/**
 * Codigos de error estables expuestos por la API. El texto visible va en espanol,
 * el identificador tecnico queda en ingles para trazar logs y metricas.
 */
public enum ErrorCode {

    INVALID_INPUT(400, "La solicitud no es valida"),
    UNAUTHORIZED(401, "Token de API invalido o ausente"),
    NOT_FOUND(404, "Recurso no encontrado"),
    CONFLICT(409, "La operacion entra en conflicto con el estado actual"),
    UNPROCESSABLE(422, "La operacion no se pudo procesar"),
    BUDGET_EXCEEDED(429, "Se agotó el presupuesto de la investigacion"),
    INTERNAL_ERROR(500, "Error interno"),
    DEPENDENCY_UNAVAILABLE(503, "Una dependencia externa no esta disponible"),
    LLM_ERROR(502, "El modelo de lenguaje fallo"),
    TOOL_ERROR(502, "La herramienta fallo");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
