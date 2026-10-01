package com.abegomez.research.agents;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Invocacion de tool que el agente ha pedido.
 *
 * <p>El protocolo es JSON en el texto, no tool calling nativo. Ollama via Spring AI
 * no expone tool calling fiable, y depender de el haria que el agente no se
 * pudiera probar sin levantar el modelo. Con un protocolo declarado, el bucle de
 * {@link ResearchAgent} se prueba con un gateway falso y el comportamiento
 * observable es el mismo.
 *
 * <p>La consecuencia es que la peticion es texto no confiable: un modelo puede
 * inventar nombres de tool. Por eso se valida contra el registro antes de
 * ejecutar nada.
 *
 * @param tool    nombre pedido
 * @param params  argumentos
 * @param valido  si se pudo interpretar
 * @param error   por que no
 */
public record ToolCall(String tool, JsonNode params, boolean valido, String error) {

    private static final String[] CLAVES = {"tool", "herramienta", "name"};
    private static final String[] CLAVES_ARGS = {"params", "args", "argumentos", "parameters"};

    /**
     * Interpreta la respuesta del agente.
     *
     * <p>Se aceptan varias claves porque los modelos alternan entre ellas. Un
     * agente que no entiende una variante valida repetiria la llamada sin
     * avanzar, y eso consume presupuesto sin producir nada.
     */
    public static ToolCall parse(String contenido, ObjectMapper objectMapper) {
        if (contenido == null || contenido.isBlank()) {
            return new ToolCall(null, null, false, "El agente no devolvio ninguna instruccion");
        }

        String texto = extraerJson(contenido);
        if (texto == null) {
            return new ToolCall(null, null, false,
                    "La respuesta no contiene JSON de invocacion. Responde solo con el JSON de la tool.");
        }

        JsonNode nodo;
        try {
            nodo = objectMapper.readTree(texto);
        }
        catch (Exception ex) {
            return new ToolCall(null, null, false, "El JSON de invocacion no es valido: " + ex.getMessage());
        }

        if (!nodo.isObject()) {
            return new ToolCall(null, null, false, "La invocacion debe ser un objeto JSON");
        }

        String nombre = null;
        for (String clave : CLAVES) {
            if (nodo.path(clave).isTextual()) {
                nombre = nodo.path(clave).asText();
                break;
            }
        }
        if (nombre == null || nombre.isBlank()) {
            return new ToolCall(null, null, false,
                    "Falta el nombre de la herramienta. Usa la clave 'tool'.");
        }

        JsonNode params = null;
        for (String clave : CLAVES_ARGS) {
            if (nodo.has(clave)) {
                params = nodo.get(clave);
                break;
            }
        }

        if (params == null || params.isNull()) {
            params = objectMapper.createObjectNode();
        }
        if (!params.isObject()) {
            return new ToolCall(nombre.toLowerCase(Locale.ROOT), null, false,
                    "Los argumentos deben ser un objeto JSON");
        }

        return new ToolCall(nombre.trim().toLowerCase(Locale.ROOT), params, true, null);
    }

    /**
     * Saca el primer objeto JSON del texto.
     *
     * <p>Un modelo rara vez devuelve solo el JSON: suele anadir una frase antes o
     * despues, o envolverlo en un bloque de codigo. Se busca el primer {@code &#123;}
     * y su cierre balanceado, en lugar de exigir que la respuesta sea exactamente
     * un JSON, porque exigirlo hace fallar llamadas que en realidad eran validas.
     */
    private static String extraerJson(String contenido) {
        String texto = contenido.trim();

        if (texto.startsWith("```")) {
            int primera = texto.indexOf('\n');
            int ultimo = texto.lastIndexOf("```");
            if (primera > 0 && ultimo > primera) {
                texto = texto.substring(primera + 1, ultimo).trim();
            }
        }

        int inicio = texto.indexOf('{');
        if (inicio < 0) {
            return null;
        }

        int profundidad = 0;
        boolean enCadena = false;
        boolean escapado = false;

        for (int i = inicio; i < texto.length(); i++) {
            char c = texto.charAt(i);

            if (enCadena) {
                if (escapado) {
                    escapado = false;
                }
                else if (c == '\\') {
                    escapado = true;
                }
                else if (c == '"') {
                    enCadena = false;
                }
                continue;
            }

            if (c == '"') {
                enCadena = true;
            }
            else if (c == '{') {
                profundidad++;
            }
            else if (c == '}') {
                profundidad--;
                if (profundidad == 0) {
                    return texto.substring(inicio, i + 1);
                }
            }
        }

        return null;
    }
}
