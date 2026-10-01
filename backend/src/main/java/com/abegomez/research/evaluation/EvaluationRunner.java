package com.abegomez.research.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.abegomez.research.evidence.CitationMatcher;
import com.abegomez.research.report.ReportRepository;
import com.abegomez.research.research.ResearchManager;
import com.abegomez.research.research.ResearchOrchestrator;
import com.abegomez.research.research.ResearchState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ejecuta el dataset de evaluacion y calcula resultados.
 *
 * <p>El criterio de puntuacion es <b>cobertura de afirmaciones esperadas</b>, no
 * parecido textual. Dos informes que dicen lo mismo con palabras distintas son
 * ambos correctos, y un comparador de cadenas los penalizaria. Cada criterio se
 * busca en el informe con la misma normalizacion que usa
 * {@link CitationMatcher}, para que acentos y mayusculas no resten puntos.
 *
 * <p>Se mide tambien si el criterio aparece <b>citado</b>, no solo mencionado.
 * Un dato que aparece sin cita no es una respuesta valida en este sistema, y el
 * dataset debe reflejar esa exigencia.
 */
@Service
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    /** Un criterio cuenta como cubierto solo si aparece al menos esta fraccion. */
    private static final double UMBRAL_COBERTURA = 0.6;

    private final EvaluationDataset dataset;
    private final ResearchManager manager;
    private final ResearchOrchestrator orchestrator;
    private final ReportRepository informes;
    private final MetricsService metrics;
    private final ObjectMapper objectMapper;

    public EvaluationRunner(EvaluationDataset dataset, ResearchManager manager,
                            ResearchOrchestrator orchestrator, ReportRepository informes,
                            MetricsService metrics, ObjectMapper objectMapper) {
        this.dataset = dataset;
        this.manager = manager;
        this.orchestrator = orchestrator;
        this.informes = informes;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /**
     * Ejecuta todos los casos del dataset.
     *
     * @param limiteCasos maximo de casos a ejecutar; null para todos
     */
    public EvaluationReport ejecutar(Integer limiteCasos) {
        List<EvaluationDataset.EvaluationCase> casos = dataset.cargar();

        if (limiteCasos != null && casos.size() > limiteCasos) {
            casos = casos.subList(0, limiteCasos);
        }

        List<EvaluationCaseResult> resultados = new ArrayList<>();
        java.time.Instant inicio = java.time.Instant.now();

        for (EvaluationDataset.EvaluationCase caso : casos) {
            if (!caso.evaluable()) {
                log.warn("Caso {} descartado: no tiene criterios de correccion", caso.id());
                resultados.add(EvaluationCaseResult.descartado(caso, "Sin criterios de correccion"));
                continue;
            }

            log.info("Evaluando caso {}: {}", caso.id(), caso.nombre());
            resultados.add(ejecutarCaso(caso));
        }

        java.time.Instant fin = java.time.Instant.now();

        return EvaluationReport.desde(resultados, java.time.Duration.between(inicio, fin));
    }

    private EvaluationCaseResult ejecutarCaso(EvaluationDataset.EvaluationCase caso) {
        long investigacionId = manager.crear(caso.pregunta(), caso.presupuestoTokens(),
                caso.maxRondas());

        ResearchOrchestrator.OrchestrationResult orquestacion;
        try {
            orquestacion = orchestrator.ejecutar(investigacionId);
        }
        catch (RuntimeException ex) {
            log.error("El caso {} fallo: {}", caso.id(), ex.getMessage());
            return EvaluationCaseResult.fallido(caso, investigacionId, ex.getMessage());
        }

        String informe = informes.ultimo(investigacionId)
                .map(ReportRepository.InformeRow::contenidoMarkdown)
                .orElse("");

        List<CriterioResultado> criterios = new ArrayList<>();
        int cubiertos = 0;
        int citados = 0;

        for (String criterio : caso.criteriosEsperados()) {
            CriterionMatch match = buscar(criterio, informe);
            boolean cubierto = match.similitud() >= UMBRAL_COBERTURA;
            boolean citado = cubierto && match.citado();

            if (cubierto) {
                cubiertos++;
            }
            if (citado) {
                citados++;
            }

            criterios.add(new CriterioResultado(criterio, cubierto, citado, match.similitud()));
        }

        var metricas = metrics.de(investigacionId);

        return new EvaluationCaseResult(caso.id(), caso.nombre(), investigacionId, true,
                criterios.size(), cubiertos, citados,
                criterios, orquestacion.aprobado(),
                orquestacion.estadoFinal().name(),
                metricas.tokensConsumidos(),
                metricas.verificacion().tasaVerificada(),
                metricas.citasInforme(),
                metricas.lineasSinCita(),
                null);
    }

    /**
     * Busca un criterio en el informe y comprueba si va citado.
     */
    private CriterionMatch buscar(String criterio, String informe) {
        if (informe == null || informe.isBlank()) {
            return new CriterionMatch(0, false);
        }

        String criterioNormalizado = CitationMatcher.normalizar(criterio);
        String informeNormalizado = CitationMatcher.normalizar(informe);

        if (informeNormalizado.contains(criterioNormalizado)) {
            // Para saber si va citado hay que mirar la frase que lo contiene,
            // no el informe entero: un criterio citado en un lado y repetido sin
            // cita en otro debe contar como no citado.
            boolean citado = apareceConCita(criterioNormalizado, informe);
            return new CriterionMatch(1.0, citado);
        }

        // Cobertura parcial: cuantas palabras clave del criterio aparecen.
        String[] palabras = criterioNormalizado.split(" ");
        int presentes = 0;
        for (String palabra : palabras) {
            if (palabra.length() > 3 && informeNormalizado.contains(palabra)) {
                presentes++;
            }
        }

        return new CriterionMatch(palabras.length == 0 ? 0 : (double) presentes / palabras.length, false);
    }

    /**
     * Si alguna linea que contiene el criterio lleva una cita.
     */
    private boolean apareceConCita(String criterioNormalizado, String informe) {
        var patronCita = java.util.regex.Pattern.compile(
                "\\[(?:evidencias?|fuente)\\s+\\d+", java.util.regex.Pattern.CASE_INSENSITIVE);

        for (String linea : informe.split("\n")) {
            String lineaNormalizada = CitationMatcher.normalizar(linea);
            if (lineaNormalizada.contains(criterioNormalizado)
                    && patronCita.matcher(linea).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param similitud cuanto se parece el criterio a alguna parte del informe
     * @param citado      si ademas lleva referencia a evidencia
     */
    private record CriterionMatch(double similitud, boolean citado) {
    }

    private record CriterioResultado(String criterio, boolean cubierto, boolean citado,
                                     double similitud) {
    }

    /**
     * Resultado de un caso.
     *
     * @param puntuacion     criterios cubiertos sobre el total, 0-1
     * @param tasaCitado     criterios cubiertos y citados sobre el total
     * @param estadoFinal    estado en que quedo la investigacion
     * @param motivoDescarte por que se descarto el caso, si aplica
     */
    public record EvaluationCaseResult(
            String id,
            String nombre,
            long investigacionId,
            boolean ejecutado,
            int totalCriterios,
            int criteriosCubiertos,
            int criteriosCitados,
            List<CriterioResultado> criterios,
            boolean informeAprobado,
            String estadoFinal,
            Long tokensConsumidos,
            Double tasaVerificacion,
            Integer citasInforme,
            Integer lineasSinCita,
            String motivoDescarte) {

        public double puntuacion() {
            if (totalCriterios == 0) {
                return 0;
            }
            return (double) criteriosCubiertos / totalCriterios;
        }

        public double tasaCitado() {
            if (totalCriterios == 0) {
                return 0;
            }
            return (double) criteriosCitados / totalCriterios;
        }

        static EvaluationCaseResult descartado(EvaluationDataset.EvaluationCase caso, String motivo) {
            return new EvaluationCaseResult(caso.id(), caso.nombre(), 0L, false,
                    caso.criteriosEsperados().size(), 0, 0, List.of(), false,
                    "DESCARTADO", null, null, null, null, motivo);
        }

        static EvaluationCaseResult fallido(EvaluationDataset.EvaluationCase caso,
                                            long investigacionId, String motivo) {
            return new EvaluationCaseResult(caso.id(), caso.nombre(), investigacionId, false,
                    caso.criteriosEsperados().size(), 0, 0, List.of(), false,
                    ResearchState.FAILED.name(), null, null, null, null, motivo);
        }
    }

    /**
     * Resultado agregado de una evaluacion.
     *
     * @param duracion duracion total de la ejecucion
     */
    public record EvaluationReport(List<EvaluationCaseResult> casos,
                                  java.time.Duration duracion) {

        public int totalCasos() {
            return casos.size();
        }

        public int casosEjecutados() {
            return (int) casos.stream().filter(EvaluationCaseResult::ejecutado).count();
        }

        public int informesAprobados() {
            return (int) casos.stream()
                    .filter(caso -> caso.informeAprobado())
                    .count();
        }

        /**
         * Puntuacion media sobre los casos ejecutados.
         *
         * <p>Solo cuenta los ejecutados: un caso fallido tiene puntuacion 0 por
         * construccion y mezclarla con la cobertura real daria un numero que no
         * describe nada.
         */
        public double puntuacionMedia() {
            List<EvaluationCaseResult> ejecutados = casos.stream()
                    .filter(EvaluationCaseResult::ejecutado)
                    .toList();
            if (ejecutados.isEmpty()) {
                return 0;
            }
            return ejecutados.stream()
                    .mapToDouble(EvaluationCaseResult::puntuacion)
                    .average()
                    .orElse(0);
        }

        public double tasaCitadoMedia() {
            List<EvaluationCaseResult> ejecutados = casos.stream()
                    .filter(EvaluationCaseResult::ejecutado)
                    .toList();
            if (ejecutados.isEmpty()) {
                return 0;
            }
            return ejecutados.stream()
                    .mapToDouble(EvaluationCaseResult::tasaCitado)
                    .average()
                    .orElse(0);
        }

        static EvaluationReport desde(List<EvaluationCaseResult> casos,
                                         java.time.Duration duracion) {
            return new EvaluationReport(List.copyOf(casos), duracion);
        }
    }
}