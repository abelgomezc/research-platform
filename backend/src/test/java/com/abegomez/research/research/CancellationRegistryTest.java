package com.abegomez.research.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests del registro de cancelacion.
 *
 * <p>La cancelacion es cooperativa, asi que lo que hay que garantizar no es que
 * detenga hilos, sino que la senal se vea en el punto de comprobacion y que no
 * se filtre de una ejecucion a la siguiente.
 */
class CancellationRegistryTest {

    @Test
    @DisplayName("Una investigacion nueva no esta cancelada")
    void noCanceladaPorDefecto() {
        CancellationRegistry registro = new CancellationRegistry();

        assertFalse(registro.estaCancelada(1L));
    }

    @Test
    @DisplayName("Solicitar cancelacion la deja marcada")
    void solicitarMarcaLaSenal() {
        CancellationRegistry registro = new CancellationRegistry();

        assertTrue(registro.solicitar(1L));
        assertTrue(registro.estaCancelada(1L));
    }

    @Test
    @DisplayName("La segunda solicitud se reconoce como repetida")
    void solicitudRepetida() {
        CancellationRegistry registro = new CancellationRegistry();

        assertTrue(registro.solicitar(1L));
        assertFalse(registro.solicitar(1L), "la segunda cancelacion no es nueva");
        assertTrue(registro.estaCancelada(1L));
    }

    @Test
    @DisplayName("La cancelacion afecta solo a la investigacion indicada")
    void noAfectaAOtras() {
        CancellationRegistry registro = new CancellationRegistry();

        registro.solicitar(1L);

        assertTrue(registro.estaCancelada(1L));
        assertFalse(registro.estaCancelada(2L));
    }

    @Test
    @DisplayName("Registrar limpia la senal de una ejecucion anterior")
    void registrarLimpiaLaSenal() {
        CancellationRegistry registro = new CancellationRegistry();
        registro.solicitar(1L);

        // Reanudar una investigacion antes cancelada no debe arrancar cancelada.
        registro.registrar(1L);

        assertFalse(registro.estaCancelada(1L),
                "una investigacion reanudada no debe heredar la cancelacion anterior");
    }

    @Test
    @DisplayName("exigirNoCancelada lanza si hay cancelacion pendiente")
    void exigirLanzaSiCancelada() {
        CancellationRegistry registro = new CancellationRegistry();
        registro.solicitar(1L);

        assertThrows(CancellationRegistry.InvestigacionCanceladaException.class,
                () -> registro.exigirNoCancelada(1L));
    }

    @Test
    @DisplayName("exigirNoCancelada no lanza si no hay cancelacion")
    void exigirNoLanzaSiNoCancelada() {
        CancellationRegistry registro = new CancellationRegistry();
        registro.registrar(1L);

        registro.exigirNoCancelada(1L);
    }

    @Test
    @DisplayName("limpiar deja de registrar la senal")
    void limpiar() {
        CancellationRegistry registro = new CancellationRegistry();
        registro.solicitar(1L);

        registro.limpiar(1L);

        assertFalse(registro.estaCancelada(1L));
    }

    @Test
    @DisplayName("La excepcion de cancelacion distingue la investigacion")
    void excepcionIdentificaInvestigacion() {
        CancellationRegistry.InvestigacionCanceladaException ex =
                new CancellationRegistry.InvestigacionCanceladaException(42L);

        assertEquals(42L, ex.investigacionId());
        assertTrue(ex.getMessage().contains("cancelada"));
    }
}
