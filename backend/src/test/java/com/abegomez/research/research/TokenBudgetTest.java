package com.abegomez.research.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests del presupuesto de tokens.
 *
 * <p>El presupuesto es una de las pocas garantias que no dependen del modelo, asi
 * que los casos limite se comprueban de forma explicita: es justo donde un
 * error de redondeo permitiria gastar de mas.
 */
class TokenBudgetTest {

    @Test
    @DisplayName("Un presupuesto nuevo no esta agotado y puede gastar")
    void presupuestoNuevo() {
        TokenBudget presupuesto = TokenBudget.de(100_000);

        assertEquals(100_000, presupuesto.restante());
        assertEquals(0.0, presupuesto.porcentajeConsumido());
        assertFalse(presupuesto.agotado());
        assertTrue(presupuesto.puedeGastar(10_000));
    }

    @Test
    @DisplayName("Consumir descuenta el restante")
    void consumir() {
        TokenBudget presupuesto = TokenBudget.de(100_000).consume(30_000);

        assertEquals(70_000, presupuesto.restante());
        assertEquals(0.3, presupuesto.porcentajeConsumido(), 0.0001);
    }

    @Test
    @DisplayName("No se permite consumir mas de lo que queda")
    void noSePuedeExceder() {
        assertThrows(TokenBudget.BudgetExceededException.class,
                () -> TokenBudget.de(1000).consume(1500));
    }

    @Test
    @DisplayName("El margen de seguridad impide la ultima llamada aunque quede margen")
    void margenDeSeguridad() {
        // Quedan 6.000 tokens, pero la llamada cuesta 3.000: sin margen, entraria.
        TokenBudget presupuesto = TokenBudget.de(6_000 + TokenBudget.MARGEN_SEGURIDAD);

        TokenBudget trasGastar = presupuesto.consume(6_000);

        assertEquals(TokenBudget.MARGEN_SEGURIDAD, trasGastar.restante());
        assertTrue(trasGastar.agotado(),
                "con el margen entero restante ya no debe permitirse ninguna llamada");
        assertFalse(trasGastar.puedeGastar(1_000));
    }

    @Test
    @DisplayName("La llamada maxima deja siempre el margen de seguridad")
    void maximoSiguienteLlamadaRespetaMargen() {
        TokenBudget presupuesto = TokenBudget.de(100_000).consume(95_000);

        long maximo = presupuesto.maximoSiguienteLlamada();

        assertEquals(100_000 - 95_000 - TokenBudget.MARGEN_SEGURIDAD, maximo);
        assertTrue(presupuesto.puedeGastar(maximo));
    }

    @Test
    @DisplayName("La llamada maxima nunca supera el tope por llamada")
    void maximoRespetaTopePorLlamada() {
        TokenBudget presupuesto = TokenBudget.de(1_000_000);

        assertEquals(TokenBudget.MAX_POR_LLAMADA, presupuesto.maximoSiguienteLlamada(),
                "con presupuesto amplio manda el tope por llamada, no el restante");
    }

    @Test
    @DisplayName("Un presupuesto de cero esta agotado de inmediato")
    void presupuestoCero() {
        TokenBudget presupuesto = TokenBudget.de(0);

        assertTrue(presupuesto.agotado());
        assertEquals(0, presupuesto.maximoSiguienteLlamada());
        assertFalse(presupuesto.puedeGastar(1));
        assertEquals(1.0, presupuesto.porcentajeConsumido());
    }

    @Test
    @DisplayName("El restante nunca es negativo")
    void restanteNoNegativo() {
        // Solo posible si el gasto se contabiliza por fuera del presupuesto.
        TokenBudget presupuesto = TokenBudget.de(100).consume(100);

        assertEquals(0, presupuesto.restante());
    }

    @Test
    @DisplayName("Se rechazan presupuestos o consumos negativos")
    void rechazaNegativos() {
        assertThrows(IllegalArgumentException.class, () -> TokenBudget.de(-1));
        assertThrows(IllegalArgumentException.class, () -> new TokenBudget(100, -5));
        assertThrows(IllegalArgumentException.class, () -> TokenBudget.de(100).consume(-10));
    }

    @Test
    @DisplayName("El gasto de cero tokens siempre es valido")
    void gastoCero() {
        assertEquals(100, TokenBudget.de(100).consume(0).presupuestoTokens());
    }
}
