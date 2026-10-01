package com.abegomez.research.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Dataset de evaluacion: casos con su respuesta esperada.
 *
 * <p>Un caso de evaluacion sin criterio de correccion no mide nada. Por eso cada
 * uno declara {@code criteriosEsperados}: afirmaciones que el informe debe
 * cubrir. El runner compara la cobertura, no el parecido textual, porque dos
 * redacciones distintas del mismo hecho son ambas correctas.
 *
 * <p>Los criterios se escriben a mano y son verificables: o el informe menciona el
 * dato o no. No se puntua el estilo.
 */
@Component
public class EvaluationDataset {

    private static final Logger log = LoggerFactory.getLogger(EvaluationDataset.class);

    private static final String RUTA_DATASET = "classpath:evaluation/dataset.json";

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    public EvaluationDataset(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Carga el dataset desde los recursos.
     */
    public List<EvaluationCase> cargar() {
        Resource recurso = resourceLoader.getResource(RUTA_DATASET);
        try (InputStream entrada = recurso.getInputStream()) {
            JsonNode raiz = objectMapper.readTree(entrada);

            List<EvaluationCase> casos = new ArrayList<>();
            for (JsonNode nodo : raiz.path("casos")) {
                casos.add(convertir(nodo));
            }

            log.info("Dataset de evaluacion cargado: {} casos", casos.size());
            return casos;
        }
        catch (IOException ex) {
            throw new IllegalStateException(
                    "No se pudo leer el dataset de evaluacion en " + RUTA_DATASET, ex);
        }
    }

    /**
     * Carga el dataset desde disco, para permitir datasets propios sin recompilar.
     */
    public List<EvaluationCase> cargarDesde(Path ruta) {
        try {
            JsonNode raiz = objectMapper.readTree(Files.readString(ruta));

            List<EvaluationCase> casos = new ArrayList<>();
            for (JsonNode nodo : raiz.path("casos")) {
                casos.add(convertir(nodo));
            }
            return casos;
        }
        catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer el dataset en " + ruta, ex);
        }
    }

    private EvaluationCase convertir(JsonNode nodo) {
        List<String> criterios = new ArrayList<>();
        for (JsonNode criterio : nodo.path("criteriosEsperados")) {
            String texto = criterio.asText("").trim();
            if (!texto.isEmpty()) {
                criterios.add(texto);
            }
        }

        return new EvaluationCase(
                nodo.path("id").asText(""),
                nodo.path("nombre").asText(""),
                nodo.path("pregunta").asText(""),
                criterios,
                nodo.path("presupuestoTokens").asLong(120_000),
                nodo.path("maxRondas").asInt(2),
                nodo.path("notas").asText(""));
    }

    /**
     * Un caso del dataset.
     *
     * @param criteriosEsperados afirmaciones que el informe debe cubrir
     * @param presupuestoTokens presupuesto de la investigacion del caso
     */
    public record EvaluationCase(
            String id,
            String nombre,
            String pregunta,
            List<String> criteriosEsperados,
            long presupuestoTokens,
            int maxRondas,
            String notas) {

        /**
         * Un caso sin criterios no se puede puntuar. Se rechaza al cargar para
         * que no entre en el resultado con un 0 que no significa nada.
         */
        public boolean evaluable() {
            return !pregunta.isBlank() && !criteriosEsperados.isEmpty();
        }
    }
}
