package com.abegomez.research.llm;

/**
 * Puerto de acceso al modelo de lenguaje. Los agentes dependen de esta interfaz
 * y no de Spring AI, lo que permite probarlos con una implementacion falsa.
 */
public interface LlmGateway {

    /**
     * Ejecuta una llamada de chat para el rol indicado y devuelve el resultado
     * con la contabilidad de tokens y la duracion ya resueltas.
     */
    LlmResult chat(AgentRole role, LlmMessages messages);

    /**
     * Ejecuta una llamada de chat con herramientas habilitadas. La implementacion
     * delegara en tool calling nativo del proveedor cuando este lo soporte.
     */
    LlmResult chatWithTools(AgentRole role, LlmMessages messages);

    /**
     * Nombre del modelo configurado para el rol.
     */
    String modelFor(AgentRole role);
}
