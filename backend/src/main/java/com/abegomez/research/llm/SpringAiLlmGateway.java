package com.abegomez.research.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.abegomez.research.common.ErrorCode;
import com.abegomez.research.common.exception.BusinessException;
import com.abegomez.research.llm.observability.LlmMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Implementacion de {@link LlmGateway} sobre Spring AI con Ollama.
 *
 * <p>Este wrapper concentra, de forma deliberada, lo que los agentes no deben
 * resolver por su cuenta: elegir el modelo por rol, aplicar parametros de
 * generacion, contar tokens (usando la metadata del proveedor y estimando cuando
 * no exista), medir la duracion y reintentar con backoff exponencial acotado.
 */
@Component
public class SpringAiLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmGateway.class);

    private final LlmModelRegistry modelRegistry;
    private final LlmProperties properties;
    private final LlmMetrics metrics;
    private final Sleeper sleeper;

    @Autowired
    public SpringAiLlmGateway(LlmModelRegistry modelRegistry, LlmProperties properties, LlmMetrics metrics) {
        this(modelRegistry, properties, metrics, Thread::sleep);
    }

    SpringAiLlmGateway(LlmModelRegistry modelRegistry, LlmProperties properties, LlmMetrics metrics,
                       Sleeper sleeper) {
        this.modelRegistry = modelRegistry;
        this.properties = properties;
        this.metrics = metrics;
        this.sleeper = sleeper;
    }

    @Override
    public LlmResult chat(AgentRole role, LlmMessages messages) {
        return execute(role, messages);
    }

    @Override
    public LlmResult chatWithTools(AgentRole role, LlmMessages messages) {
        return execute(role, messages);
    }

    @Override
    public String modelFor(AgentRole role) {
        return properties.modelFor(role);
    }

    private LlmResult execute(AgentRole role, LlmMessages messages) {
        String model = properties.modelFor(role);
        LlmProperties.Retry retry = properties.getRetry();
        RuntimeException lastFailure = null;
        int maxAttempts = Math.max(1, retry.getMaxAttempts());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long startedAt = System.nanoTime();
            try {
                return callOnce(role, model, messages, attempt, startedAt);
            }
            catch (RuntimeException ex) {
                lastFailure = ex;
                boolean canRetry = attempt < maxAttempts;
                log.warn("Llamada al modelo fallo, rol={}, modelo={}, intento={}/{}, reintentar={}",
                        role, model, attempt, maxAttempts, canRetry, ex);
                if (!canRetry) {
                    metrics.recordFailure(role, model, maxAttempts);
                    break;
                }
                sleepQuietly(backoffFor(attempt));
            }
        }

        throw new LlmCallException(
                "El modelo " + model + " fallo tras " + maxAttempts + " intentos para el rol " + role,
                lastFailure, maxAttempts);
    }

    private LlmResult callOnce(AgentRole role, String model, LlmMessages messages, int attempt, long startedAt) {
        ChatModel chatModel = modelRegistry.modelFor(role);
        if (chatModel == null) {
            throw new IllegalStateException("No hay modelo configurado para el rol " + role);
        }

        Prompt prompt = new Prompt(buildMessages(messages), optionsFor(role, model));
        int timeoutSeconds = properties.getTimeoutSeconds();

        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() -> chatModel.call(prompt));
        ChatResponse response;
        try {
            response = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Timeout de {}s excedido para rol={}, modelo={}, intento={}",
                    timeoutSeconds, role, model, attempt);
            throw new LlmCallException(
                    "Timeout de " + timeoutSeconds + "s excedido para el rol " + role
                            + " con modelo " + model, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallException(
                    "La llamada al modelo fue interrumpida para el rol " + role, e);
        } catch (ExecutionException e) {
            throw new LlmCallException(
                    "Error en la llamada al modelo para el rol " + role, e.getCause());
        }

        String content = requireContent(response, model);
        TokenCounting counting = countTokens(response, messages, content);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;

        metrics.recordSuccess(role, model, counting.input(), counting.output(), durationMs, attempt);
        log.debug("Llamada al modelo completada, rol={}, modelo={}, tokensEntrada={}, tokensSalida={}, estimados={}, duracionMs={}",
                role, model, counting.input(), counting.output(), counting.estimated(), durationMs);

        return new LlmResult(content, model, counting.input(), counting.output(),
                counting.estimated(), durationMs, attempt);
    }

    private String requireContent(ChatResponse response, String model) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new BusinessException(ErrorCode.LLM_ERROR,
                    "El modelo " + model + " devolvio una respuesta vacia");
        }
        return response.getResult().getOutput().getText();
    }

    private List<Message> buildMessages(LlmMessages messages) {
        List<Message> all = new ArrayList<>(messages.conversation().size() + 1);
        if (messages.systemPrompt() != null && !messages.systemPrompt().isBlank()) {
            all.add(LlmConversation.system(messages.systemPrompt()));
        }
        all.addAll(messages.conversation());
        return all;
    }

    private ChatOptions optionsFor(AgentRole role, String model) {
        LlmProperties.RoleParams params = properties.getParams().forRole(role);
        return OllamaChatOptions.builder()
                .model(model)
                .temperature(params.temperature())
                .numPredict(params.maxTokens())
                .build();
    }

    private TokenCounting countTokens(ChatResponse response, LlmMessages messages, String content) {
        var metadata = response.getMetadata();
        if (metadata != null && metadata.getUsage() != null) {
            Integer promptTokens = metadata.getUsage().getPromptTokens();
            Integer completionTokens = metadata.getUsage().getCompletionTokens();
            // Algunos proveedores devuelven EmptyUsage (0,0) cuando no miden el uso.
            // En ese caso el conteo real es cero y hay que estimar para no romper el presupuesto.
            boolean usageReported = promptTokens != null && completionTokens != null
                    && (promptTokens > 0 || completionTokens > 0);
            if (usageReported) {
                return new TokenCounting(promptTokens, completionTokens, false);
            }
        }
        String inputText = (messages.systemPrompt() == null ? "" : messages.systemPrompt())
                + messages.conversation().stream()
                        .map(message -> message.getText() == null ? "" : message.getText())
                        .reduce("", (a, b) -> a + "\n" + b);
        return new TokenCounting(TokenEstimator.estimate(inputText), TokenEstimator.estimate(content), true);
    }

    Duration backoffFor(int attempt) {
        LlmProperties.Retry retry = properties.getRetry();
        double factor = Math.pow(retry.getMultiplier(), attempt - 1);
        long millis = (long) Math.min(retry.getMaxBackoff().toMillis(),
                retry.getInitialBackoff().toMillis() * factor);
        return Duration.ofMillis(Math.max(0, millis));
    }

    private void sleepQuietly(Duration duration) {
        try {
            sleeper.sleep(duration.toMillis());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "La llamada al modelo fue interrumpida");
        }
    }

    /**
     * Permite simular el tiempo de espera en pruebas sin detener el hilo real.
     */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private record TokenCounting(int input, int output, boolean estimated) {
    }
}
