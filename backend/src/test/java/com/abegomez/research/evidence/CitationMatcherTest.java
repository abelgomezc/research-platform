package com.abegomez.research.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de la comprobacion determinista de citas.
 *
 * <p>Es la unica capa que no depende del modelo, asi que es la garantia real del
 * sistema: si aqui se acepta una cita inventada, nada mas la va a detectar.
 */
class CitationMatcherTest {

    private static final String FUENTE = """
            El sistema de deteccion de fraude analiza transacciones en tiempo real.
            Cuando el modelo detecta un patron anomalo, genera una alerta con un
            nivel de confianza entre 0 y 1. El equipo de seguridad revisa cada
            alerta antes de escalarla.
            """;

    @Test
    @DisplayName("Una cita copiada literalmente se verifica exacta")
    void citaExacta() {
        CitationMatcher.Resultado resultado = CitationMatcher.verificar(
                "el equipo de seguridad revisa cada alerta antes de escalarla", FUENTE);

        assertTrue(resultado.verificada());
        assertEquals(1.0, resultado.similitud());
        assertEquals("EXACTA", resultado.modo());
    }

    @Test
    @DisplayName("Las mayusculas y los acentos no invalidate una cita correcta")
    void ignoraMayusculasYAcentos() {
        CitationMatcher.Resultado resultado = CitationMatcher.verificar(
                "El equipo de seguridad revisa cada alerta antes de escalarla", FUENTE);

        assertTrue(resultado.verificada());
    }

    @Test
    @DisplayName("Un salto de linea en medio de la cita no la invalida")
    void toleraSaltosDeLinea() {
        CitationMatcher.Resultado resultado = CitationMatcher.verificar(
                "genera una alerta con un nivel de confianza", FUENTE);

        assertTrue(resultado.verificada());
    }

    @Test
    @DisplayName("Una cita con espacios dobles se acepta")
    void toleraEspacios() {
        CitationMatcher.Resultado resultado = CitationMatcher.verificar(
                "el   modelo   detecta   un  patron anomalo", FUENTE);

        assertTrue(resultado.verificada());
    }

    @Test
    @DisplayName("Una cita inventada no se verifica")
    void citaInventada() {
        CitationMatcher.Resultado resultado = CitationMatcher.verificar(
                "el modelo reduce el fraude en un 47 por ciento cada trimestre", FUENTE);

        assertFalse(resultado.verificada());
        assertTrue(resultado.detalle().contains("no aparece"));
    }

    @Test
    @DisplayName("Una cita demasiado corta se rechaza sin comparar")
    void citaMuyCorta() {
        CitationMatcher.Resultado resultado = CitationMatcher.verificar("fraude", FUENTE);

        assertFalse(resultado.verificada());
        assertTrue(resultado.detalle().contains("demasiado corta"));
    }

    @Test
    @DisplayName("Una cita vacia se rechaza")
    void citaVacia() {
        assertFalse(CitationMatcher.verificar("", FUENTE).verificada());
        assertFalse(CitationMatcher.verificar("   ", FUENTE).verificada());
        assertFalse(CitationMatcher.verificar(null, FUENTE).verificada());
    }

    @Test
    @DisplayName("Una fuente vacia no permite verificar nada")
    void fuenteVacia() {
        CitationMatcher.Resultado resultado = CitationMatcher.verificar(
                "una cita suficientemente larga para comparar", "");

        assertFalse(resultado.verificada());
        assertTrue(resultado.detalle().contains("fuente"));
    }

    @Test
    @DisplayName("La comprobacion tolerante acepta un error de transcripcion")
    void toleranciaATranscripcion() {
        // Una palabra mal escrita, el resto identico.
        CitationMatcher.Resultado resultado = CitationMatcher.verificar(
                "el equipo de seguranza revisa cada alerta antes de escalarla", FUENTE);

        assertTrue(resultado.verificada(),
                "una palabra mal transcrita no debe tirar una cita real. Similitud: "
                        + resultado.similitud());
        assertEquals("TOLERANTE", resultado.modo());
    }

    @Test
    @DisplayName("La comprobacion tolerante no acepta un texto sin relacion")
    void toleranciaNoAceptaInvento() {
        // Texto sin relacion alguna con el contenido de la fuente.
        CitationMatcher.Resultado resultado = CitationMatcher.verificar(
                "la direccion comercial del holding telefonico central de bogota",
                FUENTE);

        assertFalse(resultado.verificada(),
                "un texto sin relacion con la fuente no puede pasar como cita");
    }

    @Test
    @DisplayName("Un texto con las mismas palabras en otro orden no cuenta como la misma cita")
    void ordenImporta() {
        CitationMatcher.Resultado resultado = CitationMatcher.verificar(
                "antes de escalarla cada alerta revisa seguridad el equipo", FUENTE);

        assertFalse(resultado.verificada(),
                "reordenar las palabras cambia la cita y debe detectarse");
    }

    @Test
    @DisplayName("El fragmento devuelto incluye el contexto de la coincidencia")
    void devuelveContexto() {
        CitationMatcher.Resultado resultado = CitationMatcher.verificar(
                "el modelo detecta un patron anomalo", FUENTE);

        assertTrue(resultado.verificada());
        assertTrue(resultado.mejorFragmento().contains("modelo detecta"));
    }

    @Test
    @DisplayName("La normalizacion quita acentos y unifica espacios")
    void normalizacion() {
        assertEquals("canal de analisis", CitationMatcher.normalizar("  Canal   de análisis "));
    }

    @Test
    @DisplayName("La similitud compara por posicion, no por conjunto de caracteres")
    void similitudPosicional() {
        assertEquals(1.0, CitationMatcher.similitud("abcdef", "abcdef"), 0.0001);
        assertEquals(0.0, CitationMatcher.similitud("abcdef", "fedcba"), 0.0001);
        assertEquals(0.5, CitationMatcher.similitud("abcdef", "abcxxx"), 0.0001);
        assertEquals(0.0, CitationMatcher.similitud("", "abc"));
    }
}
