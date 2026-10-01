package com.abegomez.research.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registro de herramientas.
 *
 * <p>Un unico lugar donde vive que herramientas existen y quien puede usarlas.
 * El agente pide tools por nombre y por rol; nunca instancia herramientas por su
 * cuenta. Eso hace imposible que un agente use una tool que no le corresponde.
 *
 * <p>Preparado para MCP: cuando se expongan estas mismas fichas a un servidor
 * MCP, no habra que reescribir el registro.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ResearchTool> tools = new LinkedHashMap<>();
    private final Map<String, Map<String, ToolPermission>> permisosPorAgente = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<ResearchTool> tools, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (ResearchTool tool : tools) {
            registrar(tool);
        }
        validarEsquemas();
        log.info("ToolRegistry inicializado con {} herramientas: {}", this.tools.size(), names());
    }

    private void registrar(ResearchTool tool) {
        ToolDefinition definition = tool.definition();
        ResearchTool existente = tools.putIfAbsent(definition.name(), tool);
        if (existente != null) {
            throw new IllegalStateException("Nombre de tool duplicado: " + definition.name());
        }
    }

    private void validarEsquemas() {
        for (ResearchTool tool : tools.values()) {
            tool.definition().validateSchemas(objectMapper);
        }
    }

    /**
     * Declara que un agente puede usar una lista concreta de tools.
     *
     * <p>Se declara por rol, no por instancia. Sin esta declaracion el agente no
     * ve la tool aunque este registrada.
     */
    public void registrarPermisos(String agente, Collection<String> nombres) {
        for (String nombre : nombres) {
            ResearchTool tool = tools.get(nombre);
            if (tool == null) {
                throw new IllegalStateException(
                        "El agente '" + agente + "' declara la tool '" + nombre + "', que no esta registrada");
            }
            permisosPorAgente.computeIfAbsent(agente, key -> new LinkedHashMap<>())
                    .put(nombre, tool.definition().permission());
        }
        log.debug("Permisos de tools registrados para {}: {}", agente, nombres);
    }

    /**
     * Devuelve las tools que un agente tiene permitidas.
     */
    public List<ResearchTool> toolsDe(String agente) {
        Map<String, ToolPermission> permitidas = permisosPorAgente.get(agente);        if (permitidas == null) {
            return List.of();
        }
        return permitidas.keySet().stream()
                .map(tools::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public Optional<ResearchTool> find(String nombre) {
        return Optional.ofNullable(tools.get(nombre));
    }

    /**
     * Busca una tool y comprueba que el agente tiene permiso para usarla.
     */
    public Optional<ResearchTool> findPermitida(String agente, String nombre) {
        return toolsDe(agente).stream()
                .filter(tool -> tool.definition().name().equals(nombre))
                .findFirst();
    }

    public boolean existe(String nombre) {
        return tools.containsKey(nombre);
    }

    public List<String> names() {
        return new ArrayList<>(tools.keySet());
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(ResearchTool::definition).toList();
    }

    public int total() {
        return tools.size();
    }
}