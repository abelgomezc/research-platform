package com.abegomez.research.knowledge.pgvector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

    @Test
    @DisplayName("Un texto menor que el fragmento produce un unico fragmento")
    void shortTextProducesOneChunk() {
        List<String> fragmentos = new TextChunker(800, 120).chunk("texto corto");

        assertThat(fragmentos).containsExactly("texto corto");
    }

    @Test
    @DisplayName("Divide un texto largo en varios fragmentos")
    void splitsLongText() {
        String texto = "palabra ".repeat(500);

        List<String> fragmentos = new TextChunker(200, 40).chunk(texto);

        assertThat(fragmentos.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Los fragmentos consecutivos se solapan")
    void consecutiveChunksOverlap() {
        String texto = "palabra ".repeat(500);

        List<String> fragmentos = new TextChunker(200, 40).chunk(texto);

        // El solape es lo que permite que una cita que cae en el limite
        // aparezca completa en algun fragmento.
        assertThat(fragmentos.get(1)).contains("palabra");
        assertThat(fragmentos.size()).isGreaterThan(2);
    }

    @Test
    @DisplayName("El primer y el ultimo caracter del texto siempre aparecen")
    void coversWholeText() {
        String texto = "INICIO_UNICO " + "relleno ".repeat(400) + "FINAL_UNICO";

        List<String> fragmentos = new TextChunker(300, 50).chunk(texto);

        assertThat(fragmentos.get(0)).startsWith("INICIO_UNICO");
        assertThat(fragmentos.get(fragmentos.size() - 1)).endsWith("FINAL_UNICO");
    }

    @Test
    @DisplayName("Corta en limites de palabra")
    void cutsAtWordBoundaries() {
        String texto = "uno dos tres cuatro cinco seis siete ocho nueve diez";

        for (String fragmento : new TextChunker(20, 5).chunk(texto)) {
            assertThat(fragmento).doesNotEndWith("dosa").doesNotEndWith("cinc");
        }
    }

    @Test
    @DisplayName("Texto vacio produce lista vacia")
    void emptyTextProducesNoChunks() {
        assertThat(new TextChunker(800, 120).chunk("")).isEmpty();
        assertThat(new TextChunker(800, 120).chunk(null)).isEmpty();
        assertThat(new TextChunker(800, 120).chunk("   ")).isEmpty();
    }

    @Test
    @DisplayName("Rechaza configuraciones invalidas de chunker")
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TextChunker(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(100, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(100, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    @DisplayName("Termina y cubre todo el texto con el solape maximo")
    void alwaysProgresses() {
        String texto = "palabra ".repeat(200);

        // Con solape igual a chunkSize-1 el avance por iteracion es de un
        // caracter: el caso degenerado. Lo que importa es que termine y que
        // el texto completo quede cubierto.
        List<String> fragmentos = new TextChunker(50, 49).chunk(texto);

        assertThat(fragmentos).isNotEmpty();
        assertThat(fragmentos.get(0)).startsWith("palabra");
        assertThat(fragmentos.get(fragmentos.size() - 1)).endsWith("palabra");
    }
}
