package com.abegomez.research.llm;

/**
 * Resultado de una llamada al modelo con la contabilidad de tokens ya resuelta.
 *
 * @param content        texto devuelto por el modelo
 * @param model          modelo usado
 * @param inputTokens    tokens de entrada reportados o estimados
 * @param outputTokens   tokens de salida reportados o estimados
 * @param tokensEstimated true si los tokens se estimaron porque el proveedor no
 *                        entrego metadata de uso
 * @param durationMs     duracion de la llamada en milisegundos
 * @param attempts       intentos consumidos incluyendo el exitoso
 */
public record LlmResult(
        String content,
        String model,
        int inputTokens,
        int outputTokens,
        boolean tokensEstimated,
        long durationMs,
        int attempts) {

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
