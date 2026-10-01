package com.abegomez.research.tools;

import java.util.List;
import java.util.Map;

import com.abegomez.research.llm.AgentRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Declara que tools puede usar cada rol de agente.
 *
 * <p>La declaracion vive aqui, no en el agente ni en el prompt. Si estuviera en
 * el prompt, un texto externo podria pedirle al modelo que use una tool que no
 * le corresponde y bastaria con que el modelo cooperara. Aqui el permiso lo
 * comprueba {@link ToolExecutor} antes de ejecutar nada.
 *
 * <p>El principio es de menor privilegio: cada rol recibe lo que necesita para
 * su trabajo y nada mas. El Planner no lee datos porque no investiga; el
 * Reviewer no escribe evidencia porque no verifica que sea correcta.
 */
@Configuration
public class ToolPermissionsConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionsConfig.class);

    /**
     * Tools de lectura disponibles para cualquier agente que investigue.
     */
    private static final List<String> HERRAMIENTAS_DE_INVESTIGACION = List.of(
            ToolDefinition.Names.SEARCH_KNOWLEDGE_BASE,
            ToolDefinition.Names.SEARCH_DOCUMENTS,
            ToolDefinition.Names.READ_DOCUMENT,
            ToolDefinition.Names.SEARCH_WEB,
            ToolDefinition.Names.FETCH_PAGE,
            ToolDefinition.Names.QUERY_DATABASE);

    public ToolPermissionsConfig(ToolRegistry registry) {
        Map<AgentRole, List<String>> permisos = Map.of(
                // El Planner solo estructura: no lee fuentes ni escribe nada.
                AgentRole.PLANNER, List.of(
                        ToolDefinition.Names.GET_PREVIOUS_RESEARCH),

                // El Researcher investiga de verdad y cierra su propia tarea.
                // Sin MARK_TASK_COMPLETE no podria completar nada.
                AgentRole.RESEARCHER, concat(HERRAMIENTAS_DE_INVESTIGACION, List.of(
                        ToolDefinition.Names.SAVE_EVIDENCE,
                        ToolDefinition.Names.MARK_TASK_COMPLETE,
                        ToolDefinition.Names.CREATE_RESEARCH_TASK,
                        ToolDefinition.Names.GET_PREVIOUS_RESEARCH)),

                // El Verifier consulta fuentes para comprobar citas, pero no
                // genera evidencia nueva: anadirla contaminaria la verificacion.
                AgentRole.VERIFIER, HERRAMIENTAS_DE_INVESTIGACION,

                // El Synthesizer lee evidencia y fuentes para redactar.
                AgentRole.SYNTHESIZER, List.of(
                        ToolDefinition.Names.SEARCH_KNOWLEDGE_BASE,
                        ToolDefinition.Names.SEARCH_DOCUMENTS,
                        ToolDefinition.Names.READ_DOCUMENT,
                        ToolDefinition.Names.GET_PREVIOUS_RESEARCH),

                // El Reviewer solo necesita lo ya producido por otros.
                AgentRole.REVIEWER, List.of(
                        ToolDefinition.Names.SEARCH_DOCUMENTS,
                        ToolDefinition.Names.READ_DOCUMENT,
                        ToolDefinition.Names.GET_PREVIOUS_RESEARCH),

                // El Evaluator se ejecuta fuera de una investigacion.
                AgentRole.EVALUATOR, List.of(
                        ToolDefinition.Names.GET_PREVIOUS_RESEARCH));

        for (Map.Entry<AgentRole, List<String>> entry : permisos.entrySet()) {
            registry.registrarPermisos(entry.getKey().name(), entry.getValue());
        }

        log.info("Permisos de tools declarados para {} roles", permisos.size());
    }

    private static List<String> concat(List<String> base, List<String> extra) {
        return java.util.stream.Stream.concat(base.stream(), extra.stream()).toList();
    }
}