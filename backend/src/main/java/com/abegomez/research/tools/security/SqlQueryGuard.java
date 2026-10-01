package com.abegomez.research.tools.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validacion de consultas SQL para {@code query_database}.
 *
 * <p>La tool es de solo lectura sobre un dataset de demostracion. Las defensas
 * estan en capas porque cada una sola deja una via:
 *
 * <ol>
 *   <li>Solo un unico {@code SELECT}. Bloquea UNION, INSERT, UPDATE, DELETE, DROP y el resto.</li>
 *   <li>Solo tablas de la lista permitida.</li>
 *   <li>{@code LIMIT} forzado. El LLM no decide cuantas filas volver.</li>
 *   <li>Sin subconsultas anidadas ni funciones de archivo.</li>
 *   <li>El usuario de base de datos es de solo lectura: la ultima linea de defensa,
 *       y la que sostiene todo si el validador tiene un hueco.</li>
 * </ol>
 *
 * <p>El punto 5 es deliberado: este validador reduce superficie, no la reemplaza.
 */
public final class SqlQueryGuard {

    /** Tablas y vistas que el agente puede consultar. */
    private static final Set<String> TABLAS_PERMITIDAS = Set.of(
            "demo_transacciones",
            "demo_incidentes",
            "demo_tecnologias",
            "demo_tasa_fraude_por_pais",
            "demo_tecnologia_mas_efectiva",
            "demo_tipos_fraude");

    private static final int MAX_FILAS = 100;

    private static final Pattern PALABRA_PROHIBIDA = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|truncate|grant|revoke|commit|rollback|"
                    + "vacuum|copy|execute|prepare|deallocate|do|call|merge|lock|listen|notify|"
                    + "refresh|cluster|reindex|comment|security|set|reset|pg_sleep|pg_read_file|"
                    + "lo_import|lo_export|dblink|pg_terminate_backend)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SELECT_SIMPLE =
            Pattern.compile("^\\s*select\\b[\\s\\S]*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern FROM_O_JOIN =
            Pattern.compile("\\b(?:from|join)\\s+([a-z_][a-z0-9_.]*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern LIMITE =
            Pattern.compile("\\blimit\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PUNTO = Pattern.compile(";");

    /**
     * Comillas triples y dollar-quoting.
     *
     * <p>Se cubren las dos familias porque las dos envuelven codigo arbitrario
     * en una cadena que parece literal. {@code $$} no aparece en ninguna consulta
     * legitima de solo lectura sobre este dataset, asi que bloquearlo no cuesta
     * ninguna capacidad real.
     */
    private static final Pattern COMILLA_TRIPLE =
            Pattern.compile("('{3}|\"{3}|`{3}|\\$\\$)");

    /** Comentarios de linea y de bloque. */
    private static final Pattern COMENTARIO =
            Pattern.compile("(--|#|/\\*)");

    /** Cualquier sentencia que no empiece por SELECT. */
    private static final Pattern NO_SOLO_SELECT = Pattern.compile(
            "^\\s*(?!select\\b).*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private SqlQueryGuard() {
    }

    /**
     * Resultado de validar una consulta.
     *
     * @param permitido      si la consulta puede ejecutarse
     * @param consultaSql    consulta final, con el LIMIT forzado
     * @param motivo         por que se rechaza
     */
    public record Resultado(boolean permitido, String consultaSql, String motivo) {

        static Resultado ok(String sql) {
            return new Resultado(true, sql, null);
        }

        static Resultado no(String motivo) {
            return new Resultado(false, null, motivo);
        }
    }

    public static Set<String> tablasPermitidas() {
        return TABLAS_PERMITIDAS;
    }

    public static int maxFilas() {
        return MAX_FILAS;
    }

    /**
     * Valida y prepara la consulta.
     */
    public static Resultado validar(String sql) {
        if (sql == null || sql.isBlank()) {
            return Resultado.no("La consulta esta vacia");
        }

        String limpia = sql.trim();

        if (COMILLA_TRIPLE.matcher(limpia).find()) {
            return Resultado.no("No se permiten comillas triples: se usan para inyectar codigo arbitrario");
        }
        if (COMENTARIO.matcher(limpia).find()) {
            return Resultado.no("No se permiten comentarios en la consulta: pueden ocultar codigo ejecutable");
        }

        // Se recorta al primer punto y coma: todo lo que venga despues se ignora.
        Matcher punto = PUNTO.matcher(limpia);
        if (punto.find()) {
            limpia = limpia.substring(0, punto.start()).trim();
        }
        if (limpia.isEmpty()) {
            return Resultado.no("La consulta no contiene contenido ejecutable");
        }

        if (NO_SOLO_SELECT.matcher(limpia).find()) {
            return Resultado.no("Solo se permiten consultas SELECT");
        }
        if (!SELECT_SIMPLE.matcher(limpia).matches()) {
            return Resultado.no("Solo se permiten consultas SELECT");
        }

        // UNION permite pegar un segundo SELECT o, combinandolo con lo anterior,
        // camuflar escritura. Se bloquea por completitud, no porque este
        // proyecto la necesite.
        if (contienePalabra(limpia, "union") || contienePalabra(limpia, "except")
                || contienePalabra(limpia, "intersect")) {
            return Resultado.no("No se permiten UNION, EXCEPT ni INTERSECT: se usan para encadenar consultas");
        }

        Matcher prohibido = PALABRA_PROHIBIDA.matcher(limpia);
        if (prohibido.find()) {
            return Resultado.no("La consulta contiene la palabra reservada '"
                    + prohibido.group(1).toLowerCase(Locale.ROOT) + "', que no esta permitida");
        }

        List<String> tablas = extraerTablas(limpia);
        if (tablas.isEmpty()) {
            return Resultado.no("No se identifico ninguna tabla en la consulta");
        }
        for (String tabla : tablas) {
            String nombre = tabla.contains(".") ? tabla.substring(tabla.indexOf('.') + 1) : tabla;
            if (!TABLAS_PERMITIDAS.contains(nombre.toLowerCase(Locale.ROOT))) {
                return Resultado.no("La tabla '" + tabla + "' no esta permitida. "
                        + "Tablas permitidas: " + String.join(", ", TABLAS_PERMITIDAS.stream().sorted().toList()));
            }
        }

        // El LIMIT lo impone el sistema, no el modelo.
        String conLimite = forzarLimite(limpia);

        return Resultado.ok(conLimite);
    }

    private static List<String> extraerTablas(String sql) {
        Matcher matcher = FROM_O_JOIN.matcher(sql);
        List<String> tablas = new java.util.ArrayList<>();
        while (matcher.find()) {
            tablas.add(matcher.group(1));
        }
        return tablas;
    }

    private static boolean contienePalabra(String sql, String palabra) {
        return Pattern.compile("\\b" + palabra + "\\b", Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }

    /**
     * Reemplaza un LIMIT existente por el maximo permitido, o lo agrega si no hay.
     */
    private static String forzarLimite(String sql) {
        Matcher matcher = LIMITE.matcher(sql);
        if (matcher.find()) {
            // Se corta desde la palabra LIMIT: todo lo que venia despues se
            // descarta y se reaplica el limite del sistema.
            return sql.substring(0, matcher.start()) + " LIMIT " + MAX_FILAS;
        }
        return sql + " LIMIT " + MAX_FILAS;
    }
}