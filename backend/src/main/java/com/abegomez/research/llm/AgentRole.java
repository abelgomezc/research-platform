package com.abegomez.research.llm;

/**
 * Rol del agente que ejecuta la llamada al modelo. El modelo se resuelve por rol
 * desde configuracion, nunca desde codigo.
 */
public enum AgentRole {

    PLANNER,
    RESEARCHER,
    VERIFIER,
    SYNTHESIZER,
    REVIEWER,
    EVALUATOR
}
