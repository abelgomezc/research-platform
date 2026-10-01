package com.abegomez.research.report;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.abegomez.research.evidence.EvidenceRepository;
import com.abegomez.research.evidence.EvidenceRepository.EvidenciaRow;
import com.abegomez.research.llm.AgentRole;
import com.abegomez.research.llm.LlmGateway;
import com.abegomez.research.llm.LlmMessages;
import com.abegomez.research.llm.LlmResult;
import com.abegomez.research.research.ResearchEventType;
import com.abegomez.research.research.ResearchManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Genera el informe a partir de la evidencia verificada.
 *
 * <p>El sintetizador solo ve evidencia verificada. No se le pasa el conjunto
 * completo porque podria acabar citando algo que no se sostiene, y la
 * validacion posterior lo detectaria y rechazaria el informe entero, wasting una
 * generacion. Filtrar antes evita ese ciclo.
 *
 * <p>Si despues de filtrar no queda nada, no se genera informe. Un informe
 * honesto sin datos es una respuesta valida; uno con relleno no lo es.
 */
@Service
public class ReportSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(ReportSynthesisService.class);

    private static final String VERSION_PROMPT = "synthesizer-v1";

    /** Maximo de evidencias que entran en el prompt. */
    private static final int MAX_EVIDENCIAS_PROMPT = 60;

    private final EvidenceRepository evidencias;
    private final ReportRepository informes;
    private final LlmGateway llm;
    private final ResearchManager manager;
    private final ObjectMapper objectMapper;

    public ReportSynthesisService(EvidenceRepository evidencias, ReportRepository informes,
                                  LlmGateway llm, ResearchManager manager,
                                  ObjectMapper objectMapper) {
        this.evidencias = evidencias;
        this.informes = informes;
        this.llm = llm;
        this.manager = manager;
        this.objectMapper = objectMapper;
    }

    public String versionPrompt() {
        return VERSION_PROMPT;
    }

    /**
     * Sintetiza el informe de una investigacion.
     *
     * @return resultado con el informe generado y su validacion
     */
    @Transactional
    public SynthesisResult sintetizar(long investigacionId, String objetivo) {
        List<EvidenciaRow> verificadas = evidencias.verificadas(investigacionId);

        if (verificadas.isEmpty()) {
            log.warn("Investigacion {}: no hay evidencia verificada, no se genera informe",
                    investigacionId);

            List<String> advertencias = List.of(
                    "No se genero informe porque no hay ninguna evidencia verificada. "
                            + "El objetivo no pudo responderse con los datos disponibles.");

            String contenido = """
                    # Informe sin resultados

                    ## Objetivo
                    %s

                    ## Advertencia
                    %s

                    La investigacion se completo sin informacion verificable. No se
                    presenta ninguna conclusion porque hacerlo sin evidencia seria
                    inventarla.
                    """.formatted(objetivo, advertencias.get(0));

            long informeId = informes.guardarVersion(investigacionId, contenido, advertencias);
            informes.marcarEstado(informeId, "BORRADOR");

            return new SynthesisResult(informeId, contenido,
                    new ReportValidator.Resultado(false,
                            List.of("(no hay evidencia verificada)"), List.of(), 0, advertencias),
                    verificadas.size());
        }

        List<EvidenciaRow> paraPrompt = verificadas.size() > MAX_EVIDENCIAS_PROMPT
                ? verificadas.subList(0, MAX_EVIDENCIAS_PROMPT)
                : verificadas;

        Set<Long> idsValidos = verificadas.stream()
                .map(EvidenciaRow::id)
                .collect(Collectors.toSet());

        String contenido;
        try {
            LlmResult resultado = llm.chat(AgentRole.SYNTHESIZER,
                    LlmMessages.single(promptSistema(), promptUsuario(objetivo, paraPrompt)));

            contenido = resultado.content() == null ? "" : resultado.content();
        }
        catch (RuntimeException ex) {
            log.error("Investigacion {}: el sintetizador fallo: {}", investigacionId, ex.getMessage());
            throw ex;
        }

        ReportValidator.Resultado validacion = ReportValidator.validar(contenido, idsValidos);

        List<String> advertencias = new java.util.ArrayList<>(validacion.advertencias());
        advertencias.addAll(contradiccionesComoAdvertencia(investigacionId));

        long informeId = informes.guardarVersion(investigacionId, contenido, advertencias);
        informes.marcarEstado(informeId, validacion.aprobado() ? "BORRADOR" : "BORRADOR");

        eventoInforme(investigacionId, informeId, validacion);

        log.info("Investigacion {}: informe generado con {} evidencias, validacion {}",
                investigacionId, verificadas.size(), validacion.aprobado() ? "ok" : "con problemas");

        return new SynthesisResult(informeId, contenido, validacion, verificadas.size());
    }

    /**
     * Las contradicciones no resueltas se declaran en el informe.
     *
     * <p>No se ocultan ni se resuelven aqui. Un informe que no menciona que sus
     * fuentes se contradicen presenta como solido algo que no lo esta.
     */
    private List<String> contradiccionesComoAdvertencia(long investigacionId) {
        var contradicciones = evidencias.contradicciones(investigacionId);

        return contradicciones.stream()
                .filter(contradiccion -> !contradiccion.resuelta())
                .map(contradiccion -> "Las evidencias " + contradiccion.evidenciaAId() + " y "
                        + contradiccion.evidenciaBId() + " se contradicen: "
                        + contradiccion.descripcion())
                .toList();
    }

    private void eventoInforme(long investigacionId, long informeId,
                               ReportValidator.Resultado validacion) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("informeId", informeId);
        payload.put("aprobado", validacion.aprobado());
        payload.put("totalCitas", validacion.totalCitas());
        payload.put("lineasSinCita", validacion.lineasSinCita().size());
        payload.put("citasDesconocidas", validacion.citasDesconocidas().size());
        manager.evento(investigacionId, ResearchEventType.REPORT_GENERATED, payload);
    }

    private String promptSistema() {
        return """
                Eres un sintetizador de informes de investigacion. Escribes en markdown
                y respondes UNICAMENTE con el informe, sin comentarios previos ni
                posteriores.

                Regla central: toda afirmacion sobre los hechos debe llevar su cita
                al final, con el formato [evidencia N], donde N es el id de la evidencia
                que la sostiene. Si una afirmacion no tiene evidencia verificada que
                la sostenga, no la escribas: declarala como limite.

                Estructura:
                # Informe
                ## Objetivo
                ## Hallazgos principales
                ## Detalle
                ## Contradicciones y dudas abiertas
                ## Limites de esta investigacion

                Prohibido:
                - Afirmar algo sin cita.
                - Citar una evidencia que no aparece en la lista que se te da.
                - Suavizar un limite para que el informe parezca mas completo.
                - Inventar cifras, fechas o nombres.
                """;
    }

    private String promptUsuario(String objetivo, List<EvidenciaRow> verificadas) {
        StringBuilder bloque = new StringBuilder();
        for (EvidenciaRow evidencia : verificadas) {
            bloque.append("[evidencia ").append(evidencia.id()).append("]\n")
                    .append("  Fuente: ").append(evidencia.referencia())
                    .append(" (").append(evidencia.fuenteTipo()).append(")\n")
                    .append("  Confianza: ").append(evidencia.confianza() == null
                            ? "no declarada" : evidencia.confianza()).append('\n')
                    .append("  Afirmacion: ").append(evidencia.afirmacion()).append('\n')
                    .append("  Cita: \"").append(evidencia.citaTextual()).append("\"\n\n");
        }

        return """
                Objetivo:
                %s

                EVIDENCIA VERIFICADA (la unica que puedes citar):
                %s
                Redacta el informe citando con [evidencia N].
                """.formatted(objetivo, bloque);
    }

    /**
     * @param totalEvidencias evidencias verificadas disponibles
     */
    public record SynthesisResult(long informeId, String contenido,
                                  ReportValidator.Resultado validacion,
                                  int totalEvidencias) {

        public boolean aprobado() {
            return validacion.aprobado();
        }
    }
}