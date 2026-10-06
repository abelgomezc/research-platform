package com.abegomez.research.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion del acceso a modelos de lenguaje.
 *
 * <p>Los modelos se configuran por rol de agente para poder cambiarlos sin tocar
 * codigo. Los nombres de propiedad usan kebab-case y los roles en minusculas.
 */
    @Component
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    /**
     * Nombre del modelo de chat por rol. Ejemplo: {@code app.llm.models.planner=qwen3:8b}.
     */
    private Models models = new Models();

    /**
     * Nombre del modelo de embeddings.
     */
    private String embeddingModel = "nomic-embed-text";

    /**
     * Politica de reintentos ante fallo de infraestructura del proveedor.
     */
    private Retry retry = new Retry();

    /**
     * Parametros por rol (temperatura, tokens maximos, contexto).
     */
    private Params params = new Params();

    private int timeoutSeconds = 300;

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Models getModels() {
        return models;
    }

    public void setModels(Models models) {
        this.models = models;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Params getParams() {
        return params;
    }

    public void setParams(Params params) {
        this.params = params;
    }

    /**
     * Resuelve el modelo configurado para un rol.
     */
    public String modelFor(AgentRole role) {
        return switch (role) {
            case PLANNER -> require(models.getPlanner(), "planner");
            case RESEARCHER -> require(models.getResearcher(), "researcher");
            case VERIFIER -> require(models.getVerifier(), "verifier");
            case SYNTHESIZER -> require(models.getSynthesizer(), "synthesizer");
            case REVIEWER -> require(models.getReviewer(), "reviewer");
            case EVALUATOR -> require(models.getEvaluator(), "evaluator");
        };
    }

    private static String require(String value, String role) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta configurar app.llm.models." + role);
        }
        return value;
    }

    public static class Models {

        private String planner;
        private String researcher;
        private String verifier;
        private String synthesizer;
        private String reviewer;
        private String evaluator;

        public String getPlanner() {
            return planner;
        }

        public void setPlanner(String planner) {
            this.planner = planner;
        }

        public String getResearcher() {
            return researcher;
        }

        public void setResearcher(String researcher) {
            this.researcher = researcher;
        }

        public String getVerifier() {
            return verifier;
        }

        public void setVerifier(String verifier) {
            this.verifier = verifier;
        }

        public String getSynthesizer() {
            return synthesizer;
        }

        public void setSynthesizer(String synthesizer) {
            this.synthesizer = synthesizer;
        }

        public String getReviewer() {
            return reviewer;
        }

        public void setReviewer(String reviewer) {
            this.reviewer = reviewer;
        }

        public String getEvaluator() {
            return evaluator;
        }

        public void setEvaluator(String evaluator) {
            this.evaluator = evaluator;
        }
    }

    public static class Retry {

        /**
         * Numero maximo de intentos por llamada, incluido el primero.
         */
        private int maxAttempts = 3;

        /**
         * Espera inicial entre intentos.
         */
        private Duration initialBackoff = Duration.ofSeconds(2);

        /**
         * Multiplicador del backoff exponencial.
         */
        private double multiplier = 2.0;

        /**
         * Espera maxima entre intentos.
         */
        private Duration maxBackoff = Duration.ofSeconds(30);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }
    }

    public static class Params {

        private RoleParams planner = new RoleParams(0.2, 4096);
        private RoleParams researcher = new RoleParams(0.3, 4096);
        private RoleParams verifier = new RoleParams(0.1, 2048);
        private RoleParams synthesizer = new RoleParams(0.3, 8192);
        private RoleParams reviewer = new RoleParams(0.1, 4096);
        private RoleParams evaluator = new RoleParams(0.0, 2048);

        public RoleParams forRole(AgentRole role) {
            return switch (role) {
                case PLANNER -> planner;
                case RESEARCHER -> researcher;
                case VERIFIER -> verifier;
                case SYNTHESIZER -> synthesizer;
                case REVIEWER -> reviewer;
                case EVALUATOR -> evaluator;
            };
        }

        public RoleParams getPlanner() {
            return planner;
        }

        public void setPlanner(RoleParams planner) {
            this.planner = planner;
        }

        public RoleParams getResearcher() {
            return researcher;
        }

        public void setResearcher(RoleParams researcher) {
            this.researcher = researcher;
        }

        public RoleParams getVerifier() {
            return verifier;
        }

        public void setVerifier(RoleParams verifier) {
            this.verifier = verifier;
        }

        public RoleParams getSynthesizer() {
            return synthesizer;
        }

        public void setSynthesizer(RoleParams synthesizer) {
            this.synthesizer = synthesizer;
        }

        public RoleParams getReviewer() {
            return reviewer;
        }

        public void setReviewer(RoleParams reviewer) {
            this.reviewer = reviewer;
        }

        public RoleParams getEvaluator() {
            return evaluator;
        }

        public void setEvaluator(RoleParams evaluator) {
            this.evaluator = evaluator;
        }
    }

    /**
     * Parametros de generacion por rol.
     */
    public record RoleParams(
            @DefaultValue("0.2") double temperature,
            @DefaultValue("4096") int maxTokens) {
    }
}
