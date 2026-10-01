package com.abegomez.research.research;

/**
 * Control del presupuesto de tokens de una investigacion.
 *
 * <p>Es una de las pocas cosas que el LLM no decide. Un modelo cuya laboracion
 * se alarga puede pedir diez llamadas mas sin ninguna restriccion; sin este
 * limite, una investigacion podria consumir el presupuesto de la maquina en una
 * sola pregunta.
 *
 * <p>Los margenes existen por una razon practica: el total de tokens solo se
 * conoce <b>despues</b> de la llamada, asi que la decision de detener tiene que
 * tomarse antes de la siguiente llamada, nunca despues de la anterior.
 */
public record TokenBudget(long presupuestoTokens, long tokensConsumidos) {

    /**
     * Margen de seguridad bajo el que no se lanza ninguna llamada mas.
     *
     * <p>Sin el, una llamada iniciada con 500 tokens libres podria cerrar en 3000 y
     * dejar el presupuesto en negativo. El margen se compara con el peor caso de
     * una llamada, no con su media.
     */
    public static final long MARGEN_SEGURIDAD = 5_000L;

    /** Tope por llamada. Evita que una sola llamada se coma el presupuesto. */
    public static final long MAX_POR_LLAMADA = 20_000L;

    public TokenBudget {
        if (presupuestoTokens < 0) {
            throw new IllegalArgumentException("El presupuesto no puede ser negativo: " + presupuestoTokens);
        }
        if (tokensConsumidos < 0) {
            throw new IllegalArgumentException("Los tokens consumidos no pueden ser negativos: " + tokensConsumidos);
        }
    }

    public static TokenBudget de(long presupuesto) {
        return new TokenBudget(presupuesto, 0);
    }

    public long restante() {
        return Math.max(0, presupuestoTokens - tokensConsumidos);
    }

    /**
     * Fraccion de presupuesto ya gastada, entre 0 y 1.
     */
    public double porcentajeConsumido() {
        return presupuestoTokens == 0 ? 1.0
                : (double) tokensConsumidos / (double) presupuestoTokens;
    }

    /**
     * Si se puede permitir otra llamada de un tamano dado.
     *
     * @param tokensEstimados coste esperado de la llamada
     */
    public boolean puedeGastar(long tokensEstimados) {
        return restante() - tokensEstimados >= MARGEN_SEGURIDAD;
    }

    public boolean agotado() {
        return restante() <= MARGEN_SEGURIDAD;
    }

    /**
     * Presupuesto con los tokens ya gastados.
     *
     * @throws BudgetExceededException si se pasa del presupuesto
     */
    public TokenBudget consume(long tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("No se pueden consumir tokens negativos: " + tokens);
        }
        long nuevoConsumido = tokensConsumidos + tokens;
        if (nuevoConsumido > presupuestoTokens) {
            throw new BudgetExceededException(presupuestoTokens, nuevoConsumido);
        }
        return new TokenBudget(presupuestoTokens, nuevoConsumido);
    }

    /**
     * Tokens que se pueden gastar como maximo en la siguiente llamada.
     *
     * <p>Se devuelve un valor concreto y no un booleano porque el
     * {@code numPredict} que se pasa al modelo debe derivarse de aqui. Si solo
     * se comprobara si hay margen, el modelo podria pedir mas de lo que la
     * investigacion puede pagar.
     */
    public long maximoSiguienteLlamada() {
        return Math.min(MAX_POR_LLAMADA, Math.max(0, restante() - MARGEN_SEGURIDAD));
    }

    /**
     * Se lanza cuando un consumo supera el presupuesto.
     *
     * <p>Excepcion y no record porque necesita su propio constructor y su
     * mensaje: un record no puede declarar constructor canonico explicito.
     *
     * @param presupuesto presupuesto total
     * @param consumido   consumo que lo rebaso
     */
    public static final class BudgetExceededException extends RuntimeException {

        private final long presupuesto;
        private final long consumido;

        public BudgetExceededException(long presupuesto, long consumido) {
            super("Se agoto el presupuesto de tokens: " + consumido + " de " + presupuesto);
            this.presupuesto = presupuesto;
            this.consumido = consumido;
        }

        public long presupuesto() {
            return presupuesto;
        }

        public long consumido() {
            return consumido;
        }
    }
}
