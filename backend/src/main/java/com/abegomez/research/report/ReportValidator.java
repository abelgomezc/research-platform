package com.abegomez.research.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comprueba que el informe cumple las reglas de citacion antes de aprobarlo.
 *
 * <p>La red de seguridad del sintetizador. Un modelo puede ignorar la instruccion
 * de citar aunque se le insista; esta comprobacion existe porque la garantia no
 * puede depender de que el modelo obedezca. Si una afirmacion aparece sin cita,
 * el informe no se aprueba.
 *
 * <p>Solo comprueba forma, no verdad. Que la cita exista y sostenga la
 * afirmacion ya lo decidio la verificacion; aqui se comprueba que el documento
 * este citando lo que dice tener.
 */
public final class ReportValidator {

    /**
     * Marcador de cita: [evidencia 12] o [evidencias 3, 7].
     */
    private static final Pattern CITA =
            Pattern.compile("\\[(?:evidencias?|fuente)\\s+(\\d+(?:\\s*,\\s*\\d+)*)\\]",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Frases que declaran que algo no se pudo determinar.
     *
     * <p>No son afirmaciones de hecho y por tanto no necesitan cita. Sin esta
     * excepcion, el informe tendria que citar tambien sus propias lagunas, y eso
     * empujaria al modelo a inventar una fuente para cerrar el hueco.
     */
    private static final Pattern DECLARACION_DE_LIMITE = Pattern.compile(
            "no (se )?(pudo|pudo|se pudo|hay|consta|dispone|ha podido|se ha podido)"
                    + "|queda sin (determinar|aclarar|confirmar|resolver)"
                    + "|sin (confirmar|determinar|aclarar|evidencia|fuente|datos)"
                    + "|no (consta|aparece|figura|hay) (ninguna|evidencia|dato)"
                    + "|imposible determinar|no verificad[oa]|dato no confirmado"
                    + "|no hay (evidencia|dato|fuente|confirmaci)",
            Pattern.CASE_INSENSITIVE);

    /** Afirmaciones que no necesitan cita por ser opinion o estructura. */
    private static final Pattern NO_AFIRMATIVO = Pattern.compile(
            "^\\s*(#|>|[-*]\\s*$|\\d+\\.\\s*$)|\\bisla de (este|esta) (informe|documento|seccion)",
            Pattern.CASE_INSENSITIVE);

    private ReportValidator() {
    }

    /**
     * Resultado de validar un informe.
     *
     * @param aprobado         si el informe cumple las reglas
     * @param citasPorLinea    numero de citas detectadas en cada linea con texto
     * @param advertencias     problemas que no impiden aprobar
     */
    public record Resultado(
            boolean aprobado,
            List<String> lineasSinCita,
            List<String> citasDesconocidas,
            int totalCitas,
            List<String> advertencias) {

        public List<String> problemas() {
            List<String> todos = new ArrayList<>(lineasSinCita);
            todos.addAll(citasDesconocidas);
            return todos;
        }
    }

    /**
     * Valida el informe.
     *
     * @param markdown          contenido del informe
     * @param idsValidos        ids de evidencia que existen y estan verificadas
     */
    public static Resultado validar(String markdown, java.util.Set<Long> idsValidos) {
        List<String> sinCita = new ArrayList<>();
        List<String> desconocidas = new ArrayList<>();
        List<String> advertencias = new ArrayList<>();
        int totalCitas = 0;

        if (markdown == null || markdown.isBlank()) {
            sinCita.add("(el informe esta vacio)");
            return new Resultado(false, sinCita, desconocidas, 0, advertencias);
        }

        String[] lineas = markdown.split("\n");

        for (int numero = 1; numero <= lineas.length; numero++) {
            String linea = lineas[numero - 1].trim();

            if (linea.isEmpty() || !esTextoAfirmativo(linea)) {
                continue;
            }

            Matcher matcher = CITA.matcher(linea);
            List<Long> citadas = new ArrayList<>();

            while (matcher.find()) {
                for (String parte : matcher.group(1).split(",")) {
                    try {
                        citadas.add(Long.parseLong(parte.trim()));
                        // Se cuentan referencias a evidencia, no marcadores: un
                        // "[evidencias 1, 2]" son dos citas, y para la metrica
                        // de la evaluacion eso es lo que importa.
                        totalCitas++;
                    }
                    catch (NumberFormatException ex) {
                        advertencias.add("Linea " + numero + ": id de evidencia no numerico: " + parte.trim());
                    }
                }
            }

            if (citadas.isEmpty()) {
                sinCita.add("Linea " + numero + ": " + recortar(linea));
                continue;
            }

            for (Long id : citadas) {
                if (!idsValidos.contains(id)) {
                    desconocidas.add("Linea " + numero + ": la evidencia " + id
                            + " no existe o no esta verificada");
                }
            }
        }

        if (totalCitas == 0) {
            advertencias.add("El informe no contiene ninguna cita de evidencia");
        }

        // Las advertencias no bloquean; las afirmaciones sin cita y las citas a
        // evidencia inexistente si.
        boolean aprobado = sinCita.isEmpty() && desconocidas.isEmpty();

        return new Resultado(aprobado, sinCita, desconocidas, totalCitas, advertencias);
    }

    /**
     * Decide si una linea hace una afirmacion que necesita cita.
     */
    private static boolean esTextoAfirmativo(String linea) {
        if (NO_AFIRMATIVO.matcher(linea).find()) {
            return false;
        }
        // Encabezados, separadores y tablas de referencias.
        if (linea.startsWith("|") || linea.startsWith("---") || linea.matches("^-+\\s*$")) {
            return false;
        }
        // Una declaracion de limite ya dice que no hay dato; no necesita cita.
        return !DECLARACION_DE_LIMITE.matcher(linea).find();
    }

    private static String recortar(String texto) {
        return texto.length() <= 120 ? texto : texto.substring(0, 120) + "...";
    }
}
