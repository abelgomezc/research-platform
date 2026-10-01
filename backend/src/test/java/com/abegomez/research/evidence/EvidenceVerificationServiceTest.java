package com.abegomez.research.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.abegomez.research.llm.AgentRole;
import com.abegomez.research.llm.LlmGateway;
import com.abegomez.research.llm.LlmMessages;
import com.abegomez.research.llm.LlmResult;
import com.abegomez.research.research.ResearchManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de la verificacion en dos capas.
 *
 * <p>Lo que se comprueba aqui es el orden de las capas: una cita que no existe
 * nunca debe terminar VERIFICADA por mucho que el modelo lo afirme.
 */
class EvidenceVerificationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FUENTE = """
            El sistema de deteccion de fraude analiza transacciones en tiempo real.
            El equipo de seguridad revisa cada alerta antes de escalarla.
            """;

    private static EvidenceRepository.EvidenciaRow evidencia(String cita) {
        return new EvidenceRepository.EvidenciaRow(1L, "los alertas se revisan antes de escalarlas",
                cita, VerificationStatus.PENDIENTE, 0.8, "WEB",
                "https://ejemplo.com/a", FUENTE);
    }

    /**
     * Gateway que devuelve un veredicto fijo.
     */
    private static LlmGateway gatewayQueDice(String contenido) {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chat(org.mockito.ArgumentMatchers.eq(AgentRole.VERIFIER),
                org.mockito.ArgumentMatchers.any(LlmMessages.class)))
                .thenReturn(new LlmResult(contenido, "modelo-test", 50, 30, true, 5L, 1));
        return gateway;
    }

    private static EvidenceVerificationService servicio(LlmGateway gateway) {
        EvidenceRepository repositorio = mock(EvidenceRepository.class);
        ResearchManager manager = mock(ResearchManager.class);
        return new EvidenceVerificationService(repositorio, CitationMatcher.INSTANCIA,
                gateway, manager, MAPPER);
    }

    @Test
    @DisplayName("Cita real mas veredicto afirmativo del modelo: VERIFICADA")
    void verificacionCompleta() {
        EvidenceVerificationService servicio = servicio(gatewayQueDice(
                "{\"estado\":\"VERIFICADA\",\"confianza\":0.9,\"justificacion\":\"la cita lo dice\"}"));

        var resultado = servicio.verificarUna(evidencia(
                "El equipo de seguridad revisa cada alerta antes de escalarla"));

        assertEquals(VerificationStatus.VERIFICADA, resultado.estado());
        assertEquals(0.9, resultado.confianza());
    }

    @Test
    @DisplayName("Cita real que el modelo considera insuficiente: PARCIAL")
    void veredictoParcial() {
        EvidenceVerificationService servicio = servicio(gatewayQueDice(
                "{\"estado\":\"PARCIAL\",\"confianza\":0.5,\"justificacion\":\"no precisa el alcance\"}"));

        var resultado = servicio.verificarUna(evidencia(
                "El equipo de seguridad revisa cada alerta antes de escalarla"));

        assertEquals(VerificationStatus.PARCIAL, resultado.estado());
    }

    @Test
    @DisplayName("Una cita inventada no llega a la segunda capa")
    void citaInventadaSeDetieneEnCapaDeterminista() {
        LlmGateway gateway = gatewayQueDice(
                "{\"estado\":\"VERIFICADA\",\"confianza\":1.0,\"justificacion\":\"confio en el agente\"}");

        EvidenceVerificationService servicio = servicio(gateway);

        var resultado = servicio.verificarUna(evidencia(
                "el modelo reduce el fraude un 47 por ciento cada trimestre"));

        assertEquals(VerificationStatus.NO_VERIFICADA, resultado.estado());
        assertEquals(0.0, resultado.confianza());

        // Ni una sola llamada al modelo para una cita que no existe.
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("Si el modelo no responde, una cita confirmada queda PARCIAL, nunca VERIFICADA")
    void modeloCaidoNoVerifica() {
        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chat(org.mockito.ArgumentMatchers.eq(AgentRole.VERIFIER),
                org.mockito.ArgumentMatchers.any(LlmMessages.class)))
                .thenThrow(new RuntimeException("ollama caido"));

        EvidenceVerificationService servicio = servicio(gateway);

        var resultado = servicio.verificarUna(evidencia(
                "El equipo de seguridad revisa cada alerta antes de escalarla"));

        assertEquals(VerificationStatus.PARCIAL, resultado.estado(),
                "sin veredicto semantico no se puede afirmar VERIFICADA");
    }

    @Test
    @DisplayName("Una respuesta no interpretable degrada a PARCIAL")
    void respuestaIlegible() {
        EvidenceVerificationService servicio = servicio(gatewayQueDice("me parece correcto"));

        var resultado = servicio.verificarUna(evidencia(
                "El equipo de seguridad revisa cada alerta antes de escalarla"));

        assertEquals(VerificationStatus.PARCIAL, resultado.estado());
    }

    @Test
    @DisplayName("Un estado PENDIENTE del modelo se degrada a PARCIAL")
    void pendienteSeDegrada() {
        EvidenceVerificationService servicio = servicio(gatewayQueDice(
                "{\"estado\":\"PENDIENTE\",\"confianza\":0.3}"));

        var resultado = servicio.verificarUna(evidencia(
                "El equipo de seguridad revisa cada alerta antes de escalarla"));

        assertEquals(VerificationStatus.PARCIAL, resultado.estado(),
                "el modelo no puede dejar una evidencia sin resolver");
    }

    @Test
    @DisplayName("Un estado inventado por el modelo se rechaza")
    void estadoInventado() {
        EvidenceVerificationService servicio = servicio(gatewayQueDice(
                "{\"estado\":\"PERFECTA\",\"confianza\":0.9}"));

        var resultado = servicio.verificarUna(evidencia(
                "El equipo de seguridad revisa cada alerta antes de escalarla"));

        assertEquals(VerificationStatus.PARCIAL, resultado.estado());
    }

    @Test
    @DisplayName("La confianza del modelo se limita al intervalo valido")
    void confianzaSeAcota() {
        EvidenceVerificationService servicio = servicio(gatewayQueDice(
                "{\"estado\":\"VERIFICADA\",\"confianza\":5.0}"));

        var resultado = servicio.verificarUna(evidencia(
                "El equipo de seguridad revisa cada alerta antes de escalarla"));

        assertTrue(resultado.confianza() <= 1.0, "una confianza de 5 debe acotarse a 1");
    }

    @Test
    @DisplayName("Solo VERIFICADA puede usarse en el informe")
    void soloVerificadaEsUsable() {
        assertTrue(VerificationStatus.VERIFICADA.usableEnInforme());
        assertFalse(VerificationStatus.PARCIAL.usableEnInforme());
        assertFalse(VerificationStatus.NO_VERIFICADA.usableEnInforme());
        assertFalse(VerificationStatus.PENDIENTE.usableEnInforme());
    }

    @Test
    @DisplayName("El fragmento de la fuente viaja al prompt del verificador")
    void incluyeFragmentoEnElPrompt() {
        var capturados = new java.util.ArrayList<LlmMessages>();

        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.chat(org.mockito.ArgumentMatchers.eq(AgentRole.VERIFIER),
                org.mockito.ArgumentMatchers.any(LlmMessages.class)))
                .thenAnswer(invocacion -> {
                    capturados.add(invocacion.getArgument(1));
                    return new LlmResult("{\"estado\":\"VERIFICADA\"}", "m", 10, 10, true, 1L, 1);
                });

        EvidenceVerificationService servicio = servicio(gateway);
        servicio.verificarUna(evidencia(
                "El equipo de seguridad revisa cada alerta antes de escalarla"));

        assertEquals(1, capturados.size());
        String prompt = capturados.get(0).conversation().get(0).getText();

        assertTrue(prompt.contains("CONTENIDO EXTERNO NO VERIFICADO"),
                "el fragmento externo debe ir marcado como no confiable");
        assertTrue(prompt.contains("los alertas se revisan"));
    }
}
