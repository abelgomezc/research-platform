package com.abegomez.research.evidence;

/**
 * Veredicto de verificacion de una evidencia.
 *
 * <p>Los cuatro valores posibles, y por que existen:
 *
 * <ul>
 *   <li>{@code VERIFICADA}: la cita aparece en la fuente y sostiene la afirmacion.</li>
 *   <li>{@code NO_VERIFICADA}: la cita no aparece. Es una cita inventada o mal
 *       transcrita, y no puede usarse en el informe.</li>
 *   <li>{@code PARCIAL}: la cita aparece pero el verificador considera que no
 *       sostiene del todo la afirmacion.</li>
 *   <li>{@code PENDIENTE}: aun no se ha comprobado.</li>
 * </ul>
 *
 * <p>El valor por defecto de la tabla es {@code PENDIENTE} a proposito: una
 * evidencia nace sin verificar y solo la capa de verificacion la cambia.
 */
public enum VerificationStatus {

    PENDIENTE,
    VERIFICADA,
    NO_VERIFICADA,
    PARCIAL;

    /**
     * Solo las evidencias verificadas pueden sostener una afirmacion del informe.
     *
     * <p>Esta es la regla que evita que un dato sin comprobar llegue al documento
     * final. El sintetizador consulta esta condicion; no decide por su cuenta.
     */
    public boolean usableEnInforme() {
        return this == VERIFICADA;
    }
}
