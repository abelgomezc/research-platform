package com.abegomez.research.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.abegomez.research.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiLlmGatewayTest {

    private Map<AgentRole, ChatModel> models;
    private LlmProperties properties;
    private AtomicInteger sleeps;

    @BeforeEach
    void setUp() {
        models = new EnumMap<>(AgentRole.class);
        properties = new LlmProperties();
        models.put(AgentRole.PLANNER, modelReturning("plan generado"));
        models.put(AgentRole.RESEARCHER, modelReturning("hechos"));

        LlmProperties.Models configured = new LlmProperties.Models();
        configured.setPlanner("qwen3:8b");
        configured.setResearcher("qwen3:8b");
        configured.setVerifier("qwen3:8b");
        configured.setSynthesizer("qwen3:8b");
        configured.setReviewer("qwen3:8b");
        configured.setEvaluator("qwen3:8b");
        properties.setModels(configured);
        properties.getRetry().setInitialBackoff(Duration.ofMillis(10));
        properties.getRetry().setMaxBackoff(Duration.ofMillis(50));

        sleeps = new AtomicInteger();
    }

    @Test
    @DisplayName("Usa la metadata de uso del modelo cuando esta disponible")
    void countsTokensFromProviderMetadata() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithUsage("respuesta del modelo", 120, 45));
        models.put(AgentRole.VERIFIER, model);

        LlmGateway gateway = gateway();

        LlmResult result = gateway.chat(AgentRole.VERIFIER, LlmMessages.single("sistema", "usuario"));

        assertThat(result.content()).isEqualTo("respuesta del modelo");
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(45);
        assertThat(result.totalTokens()).isEqualTo(165);
        assertThat(result.tokensEstimated()).isFalse();
        assertThat(result.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("Estima los tokens cuando el proveedor no entrega metadata de uso")
    void estimatesTokensWhenMetadataIsMissing() {
        ChatModel model = mock(ChatModel.class);
        // ChatResponse con el constructor simple usa EmptyUsage (0,0), igual que
        // un proveedor que no mide el uso.
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(java.util.List.of(
                new Generation(new AssistantMessage("12345678")))));
        models.put(AgentRole.SYNTHESIZER, model);

        LlmResult result = gateway().chat(AgentRole.SYNTHESIZER,
                LlmMessages.single("sistema de prueba", "usuario de prueba"));

        assertThat(result.tokensEstimated()).isTrue();
        assertThat(result.inputTokens()).isEqualTo(TokenEstimator.estimate("sistema de pruebausuario de prueba"));
        assertThat(result.outputTokens()).isEqualTo(TokenEstimator.estimate("12345678"));
    }

    @Test
    @DisplayName("Reintenta con backoff y tiene exito en un intento posterior")
    void retriesUntilSuccess() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("conexion rechazada"))
                .thenReturn(responseWithUsage("ok", 10, 5));
        models.put(AgentRole.PLANNER, model);

        LlmResult result = gateway().chat(AgentRole.PLANNER, LlmMessages.single("s", "u"));

        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(sleeps.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Agota los reintentos y falla de forma controlada")
    void failsAfterMaxAttempts() {
        properties.getRetry().setMaxAttempts(3);
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("modelo caido"));
        models.put(AgentRole.PLANNER, model);

        assertThatThrownBy(() -> gateway().chat(AgentRole.PLANNER, LlmMessages.single("s", "u")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("qwen3:8b")
                .hasMessageContaining("3 intentos");

        verify(model, times(3)).call(any(Prompt.class));
        assertThat(sleeps.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Considera fallo una respuesta vacia y tambien la reintenta")
    void retriesOnEmptyResponse() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(java.util.List.of()))
                .thenReturn(responseWithUsage("contenido", 8, 4));
        models.put(AgentRole.REVIEWER, model);

        LlmResult result = gateway().chat(AgentRole.REVIEWER, LlmMessages.single("s", "u"));

        assertThat(result.content()).isEqualTo("contenido");
        assertThat(result.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("El backoff crece de forma exponencial y respeta el maximo")
    void backoffIsExponentialAndBounded() {
        SpringAiLlmGateway gateway = (SpringAiLlmGateway) gateway();

        assertThat(gateway.backoffFor(1)).isEqualTo(Duration.ofMillis(10));
        assertThat(gateway.backoffFor(2)).isEqualTo(Duration.ofMillis(20));
        assertThat(gateway.backoffFor(3)).isEqualTo(Duration.ofMillis(40));
        assertThat(gateway.backoffFor(10)).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    @DisplayName("Falla de forma clara si no hay modelo configurado para el rol")
    void failsWhenRoleHasNoModel() {
        LlmProperties.Models incomplete = new LlmProperties.Models();
        incomplete.setPlanner("qwen3:8b");
        properties.setModels(incomplete);

        LlmGateway gateway = new SpringAiLlmGateway(
                new LlmModelRegistry(mock(org.springframework.ai.ollama.api.OllamaApi.class), properties),
                properties,
                new com.abegomez.research.llm.observability.LlmMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                millis -> { });

        assertThatThrownBy(() -> gateway.chat(AgentRole.REVIEWER, LlmMessages.single("s", "u")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.llm.models.reviewer");
    }

    private LlmGateway gateway() {
        LlmModelRegistry registry = new LlmModelRegistry(
                mock(org.springframework.ai.ollama.api.OllamaApi.class), properties) {
            @Override
            public ChatModel modelFor(AgentRole role) {
                return models.get(role);
            }
        };
        return new SpringAiLlmGateway(registry, properties, new com.abegomez.research.llm.observability
                .LlmMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                millis -> sleeps.incrementAndGet());
    }

    private static ChatResponse responseWithUsage(String content, int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("qwen3:8b")
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(content))), metadata);
    }

    private static ChatModel modelReturning(String content) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithUsage(content, 1, 1));
        return model;
    }

    @Test
    @DisplayName("Una excepcion de negocio se distingue del fallo del proveedor")
    void businessExceptionIsWrapped() {
        ChatModel model = mock(ChatModel.class);
        properties.getRetry().setMaxAttempts(1);
        when(model.call(any(Prompt.class))).thenThrow(new BusinessException(
                com.abegomez.research.common.ErrorCode.BUDGET_EXCEEDED, "presupuesto agotado"));
        models.put(AgentRole.PLANNER, model);

        assertThatThrownBy(() -> gateway().chat(AgentRole.PLANNER, LlmMessages.single("s", "u")))
                .isInstanceOf(LlmCallException.class)
                .hasRootCauseInstanceOf(BusinessException.class);
    }
}
