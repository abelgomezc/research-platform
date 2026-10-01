package com.abegomez.research;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Sustituye el acceso a Ollama por dobles durante los tests de contexto, para
 * que el arranque de Spring no dependa de tener un servidor de modelos arriba.
 */
@TestConfiguration
public class TestBeansConfiguration {

    public static final int EMBEDDING_DIMENSIONS = 768;

    @Bean
    @Primary
    public OllamaApi testOllamaApi() {
        return OllamaApi.builder().baseUrl("http://localhost:11434").build();
    }

    @Bean
    @Primary
    public ChatModelDouble testChatModel() {
        return prompt -> {
            throw new UnsupportedOperationException(
                    "Los tests no deben llamar a un modelo real. Usa un doble de LlmGateway.");
        };
    }

    /**
     * Modelo de embeddings determinista basado en bolsa de palabras.
     *
     * <p>No es semantico, pero si es estable y sensible al contenido: dos textos
     * que comparten palabras producen vectores cercanos y dos textos disjuntos
     * producen vectores lejanos. Eso basta para probar el almacenamiento y la
     * busqueda por similitud de pgvector sin depender de Ollama.
     *
     * <p>La calidad semantica real se mide en la evaluacion (Fase 10) con el
     * modelo de verdad.
     */
    @Bean
    @Primary
    public EmbeddingModel testEmbeddingModel() {
        return new DeterministicEmbeddingModel(EMBEDDING_DIMENSIONS);
    }

    /**
     * Marcador para el doble de modelo de chat.
     */
    public interface ChatModelDouble extends org.springframework.ai.chat.model.ChatModel {
    }

    static class DeterministicEmbeddingModel implements EmbeddingModel {

        private final int dimensions;

        DeterministicEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = request.getInstructions().stream()
                    .map(text -> new Embedding(vectorize(text), null))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vectorize(document.getText());
        }

        private float[] vectorize(String text) {
            float[] vector = new float[dimensions];
            if (text != null) {
                for (String palabra : text.toLowerCase().split("\\W+")) {
                    if (palabra.length() < 3) {
                        continue;
                    }
                    int indice = Math.floorMod(palabra.hashCode(), dimensions);
                    vector[indice] += 1f;
                }
            }
            normalizar(vector);
            return vector;
        }

        private void normalizar(float[] vector) {
            double suma = 0;
            for (float valor : vector) {
                suma += valor * valor;
            }
            if (suma == 0) {
                return;
            }
            float norma = (float) Math.sqrt(suma);
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norma;
            }
        }
    }
}
