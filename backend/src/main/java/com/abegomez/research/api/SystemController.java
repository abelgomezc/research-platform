package com.abegomez.research.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.abegomez.research.llm.AgentRole;
import com.abegomez.research.llm.LlmProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vista de la configuracion efectiva del sistema. Sirve para verificar en
 * caliente que los modelos por rol son los esperados, sin exponer secretos.
 */
@RestController
@RequestMapping("/api/system")
@Tag(name = "Sistema")
public class SystemController {

    private final LlmProperties llmProperties;

    public SystemController(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    @GetMapping("/config")
    @Operation(summary = "Devuelve la configuracion efectiva de modelos por rol de agente")
    public ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> models = new LinkedHashMap<>();
        for (AgentRole role : AgentRole.values()) {
            models.put(role.name().toLowerCase(), llmProperties.modelFor(role));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("modelos", models);
        response.put("modeloEmbeddings", llmProperties.getEmbeddingModel());
        response.put("reintentosMaximos", llmProperties.getRetry().getMaxAttempts());
        return ResponseEntity.ok(response);
    }
}
