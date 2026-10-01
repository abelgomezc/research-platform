package com.abegomez.research.tools;

/**
 * Contexto de una invocacion de herramienta.
 *
 * <p>Lo que el codigo impone al agente. El agente no elige su investigacion ni
 * su tarea: las recibe aqui, y por eso una tool no puede escribir fuera del
 * alcance que le fue dado.
 *
 * @param investigacionId  investigacion a la que pertenece la invocacion
 * @param tareaId          tarea que origina la invocacion
 * @param agentExecutionId ejecucion del agente, para trazar que tool llamo que
 * @param agentRole        rol del agente que invoca
 */
public record ToolContext(
        Long investigacionId,
        Long tareaId,
        Long agentExecutionId,
        AgentRoleSnapshot agentRole) {

    /**
     * Copia del rol de agente, para no acoplar el framework de tools al modulo llm.
    */
    public record AgentRoleSnapshot(String nombre, String modelo) {
    }

    public static ToolContext of(Long investigacionId, Long tareaId, Long agentExecutionId,
                                 String agente, String modelo) {
        return new ToolContext(investigacionId, tareaId, agentExecutionId,
                new AgentRoleSnapshot(agente, modelo));
    }
}