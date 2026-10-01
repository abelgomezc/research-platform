package com.abegomez.research.llm;

/**
 * Estimacion de tokens cuando el proveedor no entrega metadata de uso.
 *
 * <p>La heuristica es deliberadamente simple y esta documentada como limitacion:
 * se cuentan caracteres y se divide entre cuatro, que es la regla de实践中 mas
 * usada para tokenizadores BPE. Cuando el proveedor si entrega metadata, esta
 * clase no se usa y el conteo es real.
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 4.0;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
