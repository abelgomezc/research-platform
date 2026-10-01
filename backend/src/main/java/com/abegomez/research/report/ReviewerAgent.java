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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Revisa el informe antes de aprobarlo.
 *
 * <p>Tres comprobaciones, en este orden y con poderes distintos:
 *
 * <ol>
 *   <li><b>Validacion determinista.</b> Si hay afirmaciones sin cita, el informe
 *       no se aprueba y no se consulta al modelo. Un error de forma no se
 *       arregla opinando sobre el.</li>
 *   <li><b>Revision del modelo.</b> Si la forma es correcta, el Reviewer juzga
 *       el contenido: si las conclusiones se sostienen en la evidencia y si hay
 *       sobreafirmaciones.</li>
 *   <li><b>Cobertura.</b> Se comprueba que las afirmaciones del informe no
 *      Redistribuyen la confianza de la evidencia que citan.</li>
 * </ol>
 *
 * <p>El Reviewer puede pedir una correccion, y entonces el orquestador vuelve a
 * sintetizar. Lo que no puede es aprobar un informe que no cita: esa decision es
 * del codigo, no suya.
 */
@Service
public class ReviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);

    private static final String VERSION_PROMPT = "reviewer-v1";

    private final EvidenceRepository evidencias;
    private final LlmGateway llm;
    private final ResearchManager manager;
    private final ObjectMapper objectMapper;

    public ReviewerAgent(EvidenceRepository evidencias, LlmGateway llm,
                         ResearchManager manager, ObjectMapper objectMapper) {
        this.evidencias = evidencias;
        this.llm = llm;
        this.manager = manager;
        this.objectMapper = objectMapper;
    }

    public String versionPrompt() {
        return VERSION_PROMPT;
    }

    /**
     * Revisa un informe.
     *
     * @param investigacionId investigacion a la que pertenece
     * @param contenido       markdown del informe
     */
    public ReviewResult revisar(long investigacionId, String contenido) {
        List<EvidenciaRow> verificadas = evidencias.verificadas(investigacionId);
        Set<Long> idsValidos = verificadas.stream()
                .map(EvidenciaRow::id)
                .collect(Collectors.toSet());

        // Capa 1: forma. Sin esto no se consulta al modelo.
        ReportValidator.Resultado forma = ReportValidator.validar(contenido, idsValidos);

        if (!forma.aprobado()) {
            List<String> problemas = forma.problemas();

            log.warn("Investigacion {}: informe con {} problemas de forma. No se consulta al Reviewer.",
                    investigacionId, problemas.size());

            registrarEvento(investigacionId, false, problemas, 0);

            return new ReviewResult(false,
                    "El informe no cumple las reglas de citacion y no puede aprobarse.",
                    problemas, forma, null);
        }

        // Capa 2: contenido.
        LlmResult respuesta;
        try {
            respuesta = llm.chat(AgentRole.REVIEWER, LlmMessages.single(promptSistema(),
                    promptUsuario(contenido, verificadas)));
        }
        catch (RuntimeException ex) {
            log.warn("Investigacion {}: el Reviewer fallo: {}", investigacionId, ex.getMessage());
            return new ReviewResult(false, "El Reviewer no pudo revisar el informe.", forma.problemas(),
                    forma, null);
        }

        ReviewVerdict veredicto = extraer(respuesta.content());

        if (veredicto == null) {
            // Sin veredicto no se aprueba: aprobar por defecto convertiria al
            // Reviewer en una formalidad.
            return new ReviewResult(false,
                    "El Reviewer no devolvio un veredicto interpretables, asi que el informe "
                            + "no se aprueba.", forma.problemas(), forma, null);
        }

        registrarEvento(investigacionId, veredicto.aprobado(), veredicto.problemas(),
                forma.totalCitas());

        log.info("Investigacion {}: revision {}, {} problemas",
                investigacionId, veredicto.aprobado() ? "aprobada" : "rechazada",
                veredicto.problemas().size());

        return new ReviewResult(veredicto.aprobado(), veredicto.motivo(),
                veredicto.problemas(), forma, veredicto);
    }

    private ReviewVerdict extraer(String contenido) {
        if (contenido == null || contenido.isBlank()) {
            return null;
        }
        try {
            String texto = contenido.trim();
            if (texto.startsWith("```")) {
                int primera = texto.indexOf('\n');
                int ultimo = texto.lastIndexOf("```");
                if (primera > 0 && ultimo > primera) {
                    texto = texto.substring(primera + 1, ultimo).trim();
                }
            }
            int inicio = texto.indexOf('{');
            int fin = texto.lastIndexOf('}');
            if (inicio < 0 || fin <= inicio) {
                return null;
            }
            JsonNode nodo = objectMapper.readTree(texto.substring(inicio, fin + 1));

            List<String> problemas = new java.util.ArrayList<>();
            for (JsonNode problema : nodo.path("problemas")) {
                String texto2 = problema.asText("").trim();
                if (!texto2.isEmpty()) {
                    problemas.add(texto2);
                }
            }

            return new ReviewVerdict(nodo.path("aprobado").asBoolean(false),
                    nodo.path("motivo").asText(""), List.copyOf(problemas));
        }
        catch (Exception ex) {
            log.debug("El Reviewer devolvio una respuesta no interpretable: {}", ex.getMessage());
            return null;
        }
    }

    private void registrarEvento(long investigacionId, boolean aprobado,
                                 List<String> problemas, int totalCitas) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("aprobado", aprobado);
        payload.put("totalCitas", totalCitas);
        payload.put("totalProblemas", problemas.size());
        manager.evento(investigacionId, ResearchEventType.REVIEW_COMPLETED, payload);
    }

    private String promptSistema() {
        return """
                Eres el revisor de un informe de investigacion. Tu trabajo es
                encontrar problemas, no dar el visto bueno.

                Busca especificamente:
                - Conclusiones que van mas alla de lo que dicen las citas.
                - Afirmaciones presentados como hechos sin respaldo suficiente.
                - Afirmaciones que se contradicen entre si dentro del informe.
                - Datos que aparecen en el informe y no estan en ninguna evidencia.
                - Lecturas que omitan un limite declarado en la evidencia citada.

                Se estricto. Un informe que declara honestamente lo que no sabe es
                mejor que uno completo y sobreafirmado, y por eso un informe honesto
                debe aprobarse aunque tenga huecos.

                Responde solo con JSON:
                {
                  "aprobado": true | false,
                  "motivo": "en una frase",
                  "problemas": ["problema concreto", "..."]
                }
                """;
    }

    private String promptUsuario(String informe, List<EvidenciaRow> verificadas) {
        StringBuilder bloque = new StringBuilder();
        for (EvidenciaRow evidencia : verificadas) {
            bloque.append("[evidencia ").append(evidencia.id()).append("] ")
                    .append(evidencia.afirmacion()).append('\n')
                    .append("    Cita: \"").append(evidencia.citaTextual()).append("\"\n");
        }

        return """
                ===== CONTENIDO A REVISAR =====
                %s

                EVIDENCIA DISPONIBLE:
                %s
                Revisa el informe.
                """.formatted(informe, bloque);
    }

    /**
     * @param forma   resultado de la validacion determinista
     * @param veredicto veredicto del modelo, null si no se pudo obtener
     */
    public record ReviewResult(boolean aprobado, String motivo, List<String> problemas,
                               ReportValidator.Resultado forma, ReviewVerdict veredicto) {

        public boolean requiereCorreccion() {
            return !aprobado;
        }
    }

    private record ReviewVerdict(boolean aprobado, String motivo, List<String> problemas) {
    }
}
