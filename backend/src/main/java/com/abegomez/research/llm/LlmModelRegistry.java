package com.abegomez.research.llm;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * Construye y expone un cliente de chat por rol de agente.
 *
 * <p>Se construye de forma perezosa y sin contactar a Ollama: cambiar de modelo
 * por rol es solo cambiar propiedades, sin recompilar ni reiniciar el flujo de
 * investigacion.
 */
@Component
public class LlmModelRegistry {

    private final OllamaApi ollamaApi;
    private final LlmProperties properties;
    private final Map<AgentRole, ChatModel> modelsByRole = new EnumMap<>(AgentRole.class);

    public LlmModelRegistry(OllamaApi ollamaApi, LlmProperties properties) {
        this.ollamaApi = ollamaApi;
        this.properties = properties;
    }

    public Map<AgentRole, ChatModel> modelsByRole() {
        if (modelsByRole.isEmpty()) {
            synchronized (this) {
                if (modelsByRole.isEmpty()) {
                    for (AgentRole role : AgentRole.values()) {
                        modelsByRole.put(role, buildFor(role));
                    }
                }
            }
        }
        return Map.copyOf(modelsByRole);
    }

    public ChatModel modelFor(AgentRole role) {
        return modelsByRole().get(role);
    }

    private ChatModel buildFor(AgentRole role) {
        LlmProperties.RoleParams params = properties.getParams().forRole(role);
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                    .defaultOptions(OllamaChatOptions.builder()
                            .model(properties.modelFor(role))
                            .temperature(params.temperature())
                            .numPredict(params.maxTokens())
                            .numCtx(8192)
                            .build())
                .build();
    }
}
