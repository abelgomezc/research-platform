package com.abegomez.research.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import com.abegomez.research.llm.AgentRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests del registro de tools y de los permisos por rol.
 */
class ToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Tool de mentira, sin dependencias, para probar el registro aislado. */
    private record ToolFalsa(String nombre, ToolPermission permiso) implements ResearchTool {

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition.Builder(nombre, "Tool de prueba",
                    "{\"type\":\"object\"}", "{\"type\":\"object\"}")
                    .permission(permiso)
                    .riskLevel(RiskLevel.LOW)
                    .timeout(Duration.ofSeconds(5))
                    .retries(0)
                    .build();
        }

        @Override
        public ToolResult execute(ToolContext context, com.fasterxml.jackson.databind.JsonNode parametros) {
            return ToolResult.ok("ok", Duration.ZERO, 1);
        }
    }

    @Test
    @DisplayName("Registra las tools y las expone por nombre")
    void registraTools() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new ToolFalsa("tool_uno", ToolPermission.READ),
                new ToolFalsa("tool_dos", ToolPermission.WRITE_INTERNAL)), MAPPER);

        assertEquals(2, registry.total());
        assertTrue(registry.existe("tool_uno"));
        assertTrue(registry.find("tool_dos").isPresent());
        assertFalse(registry.existe("tool_inexistente"));
    }

    @Test
    @DisplayName("Rechaza nombres duplicados en lugar de sobrescribir en silencio")
    void rechazaDuplicados() {
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(
                new ToolFalsa("misma", ToolPermission.READ),
                new ToolFalsa("misma", ToolPermission.READ)), MAPPER));
    }

    @Test
    @DisplayName("Un agente sin permisos declarados no ve ninguna tool")
    void agenteSinPermisosNoVeTools() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new ToolFalsa("tool_uno", ToolPermission.READ)), MAPPER);

        assertEquals(0, registry.toolsDe("agente_desconocido").size());
    }

    @Test
    @DisplayName("Un agente solo ve las tools que se le declararon")
    void agenteSoloVeLoDeclarado() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new ToolFalsa("tool_permitida", ToolPermission.READ),
                new ToolFalsa("tool_ajena", ToolPermission.READ)), MAPPER);
        registry.registrarPermisos("investigador", List.of("tool_permitida"));

        List<String> visibles = registry.toolsDe("investigador").stream()
                .map(tool -> tool.definition().name())
                .toList();

        assertEquals(List.of("tool_permitida"), visibles);
        assertTrue(registry.findPermitida("investigador", "tool_ajena").isEmpty(),
                "una tool registrada pero no declarada no debe ser alcanzable");
    }

    @Test
    @DisplayName("Declarar una tool inexistente falla al arrancar, no en produccion")
    void fallaAlDeclararToolInexistente() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new ToolFalsa("tool_uno", ToolPermission.READ)), MAPPER);

        assertThrows(IllegalStateException.class,
                () -> registry.registrarPermisos("agente", List.of("tool_que_no_existe")));
    }

    @Test
    @DisplayName("Rechaza una ficha con un esquema JSON invalido")
    void rechazaEsquemaInvalido() {
        ResearchTool conEsquemaRoto = new ResearchTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition.Builder("tool_rota", "descripcion",
                        "{esto no es json", "{}")
                        .build();
            }

            @Override
            public ToolResult execute(ToolContext context,
                                      com.fasterxml.jackson.databind.JsonNode parametros) {
                return ToolResult.ok("ok", Duration.ZERO, 1);
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new ToolRegistry(List.of(conEsquemaRoto), MAPPER));
    }

    @Test
    @DisplayName("Los permisos declarados cubren todos los roles y respetan el menor privilegio")
    void permisosPorRolRespetanMenorPrivilegio() {
        // Se registran las nueve tools reales: la declaracion de permisos debe
        // referenciarlas todas, y el registro falla al arrancar si falta alguna.
        List<ResearchTool> todas = new java.util.ArrayList<>();
        for (String nombre : ToolDefinition.Names.all()) {
            todas.add(new ToolFalsa(nombre, nombre.equals(ToolDefinition.Names.SAVE_EVIDENCE)
                    || nombre.equals(ToolDefinition.Names.CREATE_RESEARCH_TASK)
                    || nombre.equals(ToolDefinition.Names.MARK_TASK_COMPLETE)
                    ? ToolPermission.WRITE_INTERNAL
                    : ToolPermission.READ));
        }

        ToolRegistry registry = new ToolRegistry(todas, MAPPER);
        new ToolPermissionsConfig(registry);

        // El Planner no investiga: no debe ver ninguna tool de lectura de fuentes.
        assertEquals(List.of(ToolDefinition.Names.GET_PREVIOUS_RESEARCH),
                nombresDe(registry, AgentRole.PLANNER));

        // El Verifier comprueba citas pero no genera evidencia nueva.
        List<String> verifier = nombresDe(registry, AgentRole.VERIFIER);
        assertFalse(verifier.contains(ToolDefinition.Names.SAVE_EVIDENCE),
                "el verificador no debe poder crear su propia evidencia");
        assertFalse(verifier.contains(ToolDefinition.Names.CREATE_RESEARCH_TASK),
                "el verificador no debe crear tareas nuevas");
        assertTrue(verifier.contains(ToolDefinition.Names.READ_DOCUMENT));

        // El Researcher es el unico que guarda evidencia y crea tareas.
        List<String> researcher = nombresDe(registry, AgentRole.RESEARCHER);
        assertTrue(researcher.contains(ToolDefinition.Names.SAVE_EVIDENCE));
        assertTrue(researcher.contains(ToolDefinition.Names.CREATE_RESEARCH_TASK));
        assertTrue(researcher.contains(ToolDefinition.Names.MARK_TASK_COMPLETE));
        assertTrue(researcher.contains(ToolDefinition.Names.SEARCH_WEB));
        assertTrue(researcher.contains(ToolDefinition.Names.QUERY_DATABASE));

        // Nadie salvo el Researcher escribe tareas nuevas.
        List<String> revisor = nombresDe(registry, AgentRole.REVIEWER);
        assertFalse(revisor.contains(ToolDefinition.Names.SAVE_EVIDENCE));
        assertFalse(revisor.contains(ToolDefinition.Names.CREATE_RESEARCH_TASK));
        assertFalse(revisor.contains(ToolDefinition.Names.MARK_TASK_COMPLETE));

        // El Evaluator no investiga ni escribe: solo consulta el contexto.
        assertEquals(List.of(ToolDefinition.Names.GET_PREVIOUS_RESEARCH),
                nombresDe(registry, AgentRole.EVALUATOR));
    }

    private static List<String> nombresDe(ToolRegistry registry, AgentRole rol) {
        return registry.toolsDe(rol.name()).stream()
                .map(tool -> tool.definition().name())
                .sorted()
                .toList();
    }
}