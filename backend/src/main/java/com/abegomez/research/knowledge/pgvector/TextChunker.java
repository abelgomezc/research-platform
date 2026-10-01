package com.abegomez.research.knowledge.pgvector;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragmentador de texto con solape.
 *
 * <p>El solape existe para que un hecho que cae justo en el limite entre dos
 * fragmentos quede completo en alguno de los dos. Sin solape, una cita textual
 * podria no aparecer literally en ningun fragmento y la verificacion
 * determinista de la Fase 6 la rechazaria.
 *
 * <p>Los cortes se hacen en limites de palabra para no partir palabras por la
 * mitad.
 */
public final class TextChunker {

    private final int chunkSize;
    private final int overlap;

    public TextChunker(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize debe ser mayor que cero");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap debe estar entre 0 y chunkSize-1 (recibido " + overlap + ")");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * Divide el texto en fragmentos solapados.
     *
     * @return lista de fragmentos, nunca nula. Un texto menor que el tamano de
     *         fragmento produce un unico fragmento.
     */
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.length() <= chunkSize) {
            return List.of(normalized);
        }

        List<String> fragments = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());
            if (end < normalized.length()) {
                end = endAtWordBoundary(normalized, start, end);
            }
            String fragment = normalized.substring(start, end).trim();
            if (!fragment.isEmpty()) {
                fragments.add(fragment);
            }
            if (end >= normalized.length()) {
                break;
            }
            // Retrocede para crear el solape. Se garantiza avanzar al menos un
            // caracter para no entrar en bucle.
            start = Math.max(start + 1, end - overlap);
        }
        return List.copyOf(fragments);
    }

    private int endAtWordBoundary(String text, int start, int end) {
        int candidate = end;
        int searched = 0;
        while (candidate > start && searched < chunkSize / 2) {
            char current = text.charAt(candidate - 1);
            if (Character.isWhitespace(current)) {
                return candidate;
            }
            candidate--;
            searched++;
        }
        // No hay espacio: cortar en el limite duro es preferible a perder texto.
        return end;
    }

    public int chunkSize() {
        return chunkSize;
    }

    public int overlap() {
        return overlap;
    }
}
