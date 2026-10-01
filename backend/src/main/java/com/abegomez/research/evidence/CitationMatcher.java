package com.abegomez.research.evidence;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Comprobacion determinista de que una cita aparece en su fuente.
 *
 * <p>Esta es la primera de las dos capas de verificacion y es la que mas valor
 * aporta, porque no depende del modelo. Una cita que se dice textual pero no
 * aparece en el texto de la fuente es una cita inventada, y ningun juicio
 * posterior del LLM deberia poder convertirla en evidencia valida.
 *
 * <p>El emparejamiento normaliza para que diferencias de tipografia no hagan
 * fallar una cita correcta: acentos, mayusculas, espacios y guiones no cambian el
 * texto. Lo que si cambia es la palabra, y para eso la comparacion es estricta.
 *
 * <p>Un modelo puede dictar mal una palabra y aun asi el texto es legitimo. Por
 * eso hay dos tolerancias y no una: la coincidencia exacta siempre gana, y la
 * tolerante solo se usa cuando la exacta falla, aceptando el riesgo de que una
 * cita casi correcta pase por buena. Se prefiere un falso positivo sobre perder
 * citas validas por un acento.
 */
public final class CitationMatcher {

    /**
     * Instancia unica y sin estado: el matcher es puro y se puede compartir.
     */
    public static final CitationMatcher INSTANCIA = new CitationMatcher();

    /**
     * Distancia maxima de edicion admitida en la comprobacion tolerante.
     *
     * <p>Con 0.34, una palabra mal transcrita entre tres o cuatro pasa. Es mas
     * alto de lo que parece, y es deliberado: la capa semantica del verificador
     * es la que juzga si la cita sostiene la afirmacion, no si la palabra exacta
     * coincide.
     */
    private static final double UMBRAL_TOLERANCIA = 0.34;

    /** Longitud minima de la cita para que la coincidencia sea significativa. */
    private static final int LONGITUD_MINIMA = 15;

    private CitationMatcher() {
    }

    /**
     * Resultado de comprobar una cita.
     *
     * @param verificada       si la cita aparece en la fuente
     * @param similitud        0-1, cuanto se parece la cita al mejor fragmento
     * @param mejorFragmento    zona de la fuente donde aparece
     * @param modo             como se comprobo
     */
    public record Resultado(
            boolean verificada,
            double similitud,
            String mejorFragmento,
            String modo,
            String detalle) {

        static Resultado ok(double similitud, String fragmento, String modo) {
            return new Resultado(true, similitud, fragmento, modo,
                    similitud >= 1.0 ? "Coincidencia exacta" : "Coincidencia tolerante");
        }

        static Resultado no(double similitud, String detalle) {
            return new Resultado(false, similitud, null, "SIN COINCIDENCIA", detalle);
        }
    }

    /**
     * Busca la cita dentro del texto de la fuente.
     *
     * @param cita   cita textual tal como la escribio el agente
     * @param fuente texto completo de la fuente
     */
    public static Resultado verificar(String cita, String fuente) {
        if (cita == null || cita.isBlank()) {
            return Resultado.no(0, "La cita esta vacia");
        }
        if (fuente == null || fuente.isBlank()) {
            return Resultado.no(0, "La fuente no tiene texto para comparar");
        }
        if (cita.length() < LONGITUD_MINIMA) {
            return Resultado.no(0, "La cita es demasiado corta (" + cita.length()
                    + " caracteres) para considerarla evidencia");
        }

        String citaNormalizada = normalizar(cita);
        String fuenteNormalizada = normalizar(fuente);

        int posicion = fuenteNormalizada.indexOf(citaNormalizada);
        if (posicion >= 0) {
            return Resultado.ok(1.0, recortar(fuente, posicion, cita.length()), "EXACTA");
        }

        // La cita puede partir una palabra en la fuente por un salto de linea o
        // por la fragmentacion. Se comprueba tambien contra la fuente sin espacios.
        String citaCompacta = citaNormalizada.replace(" ", "");
        String fuenteCompacta = fuenteNormalizada.replace(" ", "");
        int posicionCompacta = fuenteCompacta.indexOf(citaCompacta);
        if (posicionCompacta >= 0) {
            return Resultado.ok(0.98,
                    recortar(fuente, Math.max(0, posicionCompacta / 2), cita.length()), "SIN_ESPACIOS");
        }

        // Ultima capa: la cita puede estar invertida respecto de la fuente, como
        // ocurre al citar una tabla leida de izquierda a derecha.
        String citaInvertida = new StringBuilder(citaNormalizada).reverse().toString();
        int posicionInvertida = fuenteCompacta.indexOf(citaInvertida.replace(" ", ""));
        if (posicionInvertida >= 0) {
            return Resultado.ok(0.9,
                    recortar(fuente, Math.max(0, posicionInvertida / 2), cita.length()), "INVERTIDA");
        }

        // Ultima capa: comparacion por ventanas. Se mide cuanto se parece la cita
        // a cada zona del mismo tamaño de la fuente y se acepta la mejor. Cubre
        // el caso real mas comun: el agente transcribio bien el texto pero le
        // cambio una palabra o se salto un signo.
        int longitudVentana = citaNormalizada.length();
        if (longitudVentana >= LONGITUD_MINIMA) {
            double mejorSimilitud = 0;
            int mejorPosicion = -1;

            for (int inicio = 0; inicio + longitudVentana <= fuenteNormalizada.length(); inicio++) {
                String ventana = fuenteNormalizada.substring(inicio, inicio + longitudVentana);
                double similitud = similitud(citaNormalizada, ventana);
                if (similitud > mejorSimilitud) {
                    mejorSimilitud = similitud;
                    mejorPosicion = inicio;
                }
            }

            if (mejorPosicion >= 0 && mejorSimilitud >= UMBRAL_TOLERANCIA) {
                return Resultado.ok(mejorSimilitud,
                        recortar(fuente, mejorPosicion, longitudVentana), "TOLERANTE");
            }
            return Resultado.no(mejorSimilitud,
                    "La cita no aparece en la fuente. La mejor coincidencia fue de "
                            + Math.round(mejorSimilitud * 100) + "%, por debajo del "
                            + Math.round(UMBRAL_TOLERANCIA * 100) + "% necesario.");
        }

        return Resultado.no(0,
                "La cita no aparece en el texto de la fuente. Puede estar mal transcrita "
                        + "o haber sido inventada.");
    }

    /**
     * Similitud entre dos textos de la misma longitud como razon de caracteres
     * coincidentes en la misma posicion.
     *
     * <p>Se comparan por posicion y no como conjuntos porque el orden de las
     * palabras es lo que distingue una cita real de una que solo repite las
     * mismas palabras en otro orden.
     */
    static double similitud(String a, String b) {
        int n = Math.min(a.length(), b.length());
        if (n == 0) {
            return 0;
        }
        int iguales = 0;
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) == b.charAt(i)) {
                iguales++;
            }
        }
        return (double) iguales / (double) Math.max(a.length(), b.length());
    }

    /**
     * Normaliza para comparar: sin acentos, sin mayusculas, con espacios unicos.
     *
     * <p>Publica porque la evaluacion necesita exactamente la misma
     * normalizacion. Si cada modulo normalizara por su cuenta, un criterio del
     * dataset y una cita podrian aparecer iguales o distintos segun quien
     * comparase, y la metrica dependeria de una convencion invisible.
     */
    public static String normalizar(String texto) {
        String sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        return sinAcentos.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\u00a0]+", " ")
                .replace(' ', ' ')
                .trim();
    }

    /**
     * Zona de la fuente alrededor de la coincidencia, para que el verificador
     * pueda ver el contexto.
     */
    private static String recortar(String fuente, int posicion, int longitudCita) {
        int inicio = Math.max(0, posicion - 120);
        int fin = Math.min(fuente.length(), posicion + longitudCita + 120);
        String fragmento = fuente.substring(inicio, fin);
        return (inicio > 0 ? "..." : "") + fragmento + (fin < fuente.length() ? "..." : "");
    }
}
