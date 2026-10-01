package com.abegomez.research.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contrato comun a las dos implementaciones de conocimiento interno.
 *
 * <p>Los metodos marcados con {@code @Test} se heredan y se ejecutan en cada
 * implementacion. Esa es la garantia buscada: el agente no puede distinguir si
 * detras de {@link KnowledgeSearchPort} hay pgvector o LocalRAG.
 *
 * <p>Implementaciones:
 * <ul>
 *   <li>{@code PgVectorKnowledgeSearchAdapterTest}: con dobles.</li>
 *   <li>{@code KnowledgePortContractPgVectorIT}: contra PostgreSQL real con pgvector.</li>
 *   <li>{@code LocalRagKnowledgeSearchAdapterTest}: contra el servidor HTTP simulado.</li>
 * </ul>
 */
abstract class KnowledgePortContract {

    /**
     * Devuelve la implementacion bajo prueba, ya configurada.
     */
    abstract KnowledgeSearchPort port();

    /**
     * Consulta que el corpus de demostracion debe recuperar.
     */
    String consultaDePrueba() {
        return "deteccion de fraude con machine learning";
    }

    @Test
    @DisplayName("Una consulta vacia devuelve lista vacia sin fallar")
    void consultaVaciaDevuelveVacio() {
        assertThat(port().search("", 5)).isEmpty();
        assertThat(port().search("   ", 5)).isEmpty();
        assertThat(port().search(null, 5)).isEmpty();
    }

    @Test
    @DisplayName("Nunca devuelve mas resultados que el topK solicitado")
    void respetaElTopK() {
        List<KnowledgeResult> resultados = port().search(consultaDePrueba(), 2);
        assertThat(resultados).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Todo resultado trae texto, referencia y puntaje")
    void resultadosTienenTodosLosCampos() {
        for (KnowledgeResult resultado : port().search(consultaDePrueba(), 3)) {
            assertThat(resultado.texto()).isNotBlank();
            assertThat(resultado.documentoId()).isNotBlank();
            assertThat(resultado.documentoTitulo()).isNotBlank();
            assertThat(resultado.referencia()).isNotBlank();
            assertThat(resultado.puntaje()).isBetween(0.0, 1.0);
        }
    }

    @Test
    @DisplayName("Identifica su proveedor")
    void identificaProveedor() {
        assertThat(port().providerId()).isIn("pgvector", "localrag");
    }

    @Test
    @DisplayName("Una consulta real no lanza excepcion")
    void consultaRealNoFalla() {
        port().search(consultaDePrueba(), 3);
    }
}
