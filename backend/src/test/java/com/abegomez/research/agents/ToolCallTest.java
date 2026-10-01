package com.abegomez.research.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests del parser de invocaciones de tool.
 *
 * <p>El parser es la frontera donde entra texto del modelo. Lo que no se
 * interprete bien aqui acaba ejecutandose como comando, asi que se prueban tanto
 * las variantes que devuelve un modelo real como las que no deberia devolver.
 */
class ToolCallTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Interpreta una invocacion directa")
    void invocacionDirecta() {
        ToolCall llamada = ToolCall.parse(
                "{\"tool\": \"search_web\", \"params\": {\"query\": \"fraude\"}}", MAPPER);

        assertTrue(llamada.valido());
        assertEquals("search_web", llamada.tool());
        assertEquals("fraude", llamada.params().path("query").asText());
    }

    @Test
    @DisplayName("Acepta las variantes de nombre que usa el modelo")
    void variantesDeNombre() {
        assertEquals("search_web", ToolCall.parse("{\"herramienta\": \"search_web\"}", MAPPER).tool());
        assertEquals("search_web", ToolCall.parse("{\"name\": \"search_web\"}", MAPPER).tool());
    }

    @Test
    @DisplayName("Acepta las variantes de argumentos")
    void variantesDeArgumentos() {
        assertTrue(ToolCall.parse("{\"tool\":\"a\",\"args\":{\"x\":1}}", MAPPER).valido());
        assertTrue(ToolCall.parse("{\"tool\":\"a\",\"argumentos\":{\"x\":1}}", MAPPER).valido());
        assertTrue(ToolCall.parse("{\"tool\":\"a\",\"parameters\":{\"x\":1}}", MAPPER).valido());
    }

    @Test
    @DisplayName("Saca el JSON si el modelo anade texto alrededor")
    void textoAlrededor() {
        ToolCall llamada = ToolCall.parse(
                "Claro, voy a buscar eso:\n{\"tool\":\"search_web\",\"params\":{\"query\":\"x\"}}\nListo.",
                MAPPER);

        assertTrue(llamada.valido());
        assertEquals("search_web", llamada.tool());
    }

    @Test
    @DisplayName("Saca el JSON de un bloque de codigo")
    void bloqueDeCodigo() {
        ToolCall llamada = ToolCall.parse(
                "```json\n{\"tool\":\"fetch_page\",\"params\":{\"url\":\"https://x.com\"}}\n```",
                MAPPER);

        assertTrue(llamada.valido());
        assertEquals("fetch_page", llamada.tool());
    }

    @Test
    @DisplayName("Normaliza el nombre a minusculas")
    void normalizaNombre() {
        assertEquals("mark_task_complete",
                ToolCall.parse("{\"tool\":\"MARK_TASK_COMPLETE\"}", MAPPER).tool());
    }

    @Test
    @DisplayName("Sin argumentos, usa un objeto vacio")
    void sinArgumentos() {
        ToolCall llamada = ToolCall.parse("{\"tool\":\"get_previous_research\"}", MAPPER);

        assertTrue(llamada.valido());
        assertTrue(llamada.params().isObject());
        assertEquals(0, llamada.params().size());
    }

    @Test
    @DisplayName("No confunde una llave dentro de un valor con el fin del objeto")
    void llavesEnCadena() {
        ToolCall llamada = ToolCall.parse(
                "{\"tool\":\"fetch_page\",\"params\":{\"url\":\"https://x.com/{a}\"}}", MAPPER);

        assertTrue(llamada.valido());
        assertEquals("https://x.com/{a}", llamada.params().path("url").asText());
    }

    @Test
    @DisplayName("Rechaza una respuesta sin JSON")
    void sinJson() {
        ToolCall llamada = ToolCall.parse("No se que herramienta usar", MAPPER);

        assertFalse(llamada.valido());
        assertTrue(llamada.error().contains("JSON"));
    }

    @Test
    @DisplayName("Rechaza una respuesta vacia")
    void vacia() {
        assertFalse(ToolCall.parse(null, MAPPER).valido());
        assertFalse(ToolCall.parse("", MAPPER).valido());
        assertFalse(ToolCall.parse("   ", MAPPER).valido());
    }

    @Test
    @DisplayName("Rechaza JSON malformado")
    void jsonMalformado() {
        assertFalse(ToolCall.parse("{\"tool\": \"search_web\", ", MAPPER).valido());
    }

    @Test
    @DisplayName("Rechaza un JSON que no es un objeto")
    void noEsObjeto() {
        assertFalse(ToolCall.parse("[1, 2, 3]", MAPPER).valido());
    }

    @Test
    @DisplayName("Rechaza una invocacion sin nombre de tool")
    void sinNombre() {
        ToolCall llamada = ToolCall.parse("{\"params\":{\"query\":\"x\"}}", MAPPER);

        assertFalse(llamada.valido());
        assertTrue(llamada.error().contains("tool"));
    }

    @Test
    @DisplayName("Rechaza argumentos que no son un objeto")
    void argumentosNoObjeto() {
        ToolCall llamada = ToolCall.parse("{\"tool\":\"a\",\"params\":\"texto\"}", MAPPER);

        assertFalse(llamada.valido());
        assertTrue(llamada.error().contains("objeto"));
    }

    @Test
    @DisplayName("Un JSON con llaves desbalanceadas se rechaza en vez de truncarse")
    void llavesDesbalanceadas() {
        assertFalse(ToolCall.parse("{\"tool\": \"a\", \"params\": {\"x\": 1", MAPPER).valido());
    }
}
