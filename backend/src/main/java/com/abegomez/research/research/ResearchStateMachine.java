package com.abegomez.research.research;

import static com.abegomez.research.research.ResearchState.CANCELLED;
import static com.abegomez.research.research.ResearchState.COMPLETED;
import static com.abegomez.research.research.ResearchState.CREATED;
import static com.abegomez.research.research.ResearchState.FAILED;
import static com.abegomez.research.research.ResearchState.INTERRUPTED;
import static com.abegomez.research.research.ResearchState.PLANNING;
import static com.abegomez.research.research.ResearchState.RESEARCHING;
import static com.abegomez.research.research.ResearchState.REVIEWING;
import static com.abegomez.research.research.ResearchState.SYNTHESIZING;
import static com.abegomez.research.research.ResearchState.VERIFYING;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.abegomez.research.common.ErrorCode;
import com.abegomez.research.common.exception.BusinessException;

/**
 * Transiciones validas entre estados de investigacion.
 *
 * <p>La tabla vive en codigo y no en la base de datos a proposito. El
 * {@code CHECK} de la tabla restringe los valores, pero no las transiciones: si
 * una tabla acepta los diez estados, nada impide que el codigo salte de
 * {@code CREATED} a {@code COMPLETED}. Aqui esa combinacion es imposible.
 *
 * <p>Se declara aparte del enum para que anadir un estado no conceda por
 * accidente todos los permisos de flujo. Un estado nuevo entra en
 * {@link #DESDE_ESTADOS_NUEVOS} de forma explicita.
 */
public final class ResearchStateMachine {

    /**
     * Transiciones permitidas.
     *
     * <p>La forma es deliberada: cada estado declara a donde puede ir. Una tabla
     * de transiciones invertida obligaria a buscarla, y anadir un estado nuevo
     * con permisos amplios sin querer seria mas facil.
     */
    private static final Map<ResearchState, Set<ResearchState>> TRANSICIONES = Map.of(
            CREATED, EnumSet.of(PLANNING, FAILED, CANCELLED, INTERRUPTED),
            PLANNING, EnumSet.of(RESEARCHING, FAILED, CANCELLED, INTERRUPTED),
            RESEARCHING, EnumSet.of(VERIFYING, RESEARCHING, FAILED, CANCELLED, INTERRUPTED),
            VERIFYING, EnumSet.of(SYNTHESIZING, RESEARCHING, FAILED, CANCELLED, INTERRUPTED),
            SYNTHESIZING, EnumSet.of(REVIEWING, FAILED, CANCELLED, INTERRUPTED),
            REVIEWING, EnumSet.of(SYNTHESIZING, COMPLETED, FAILED, CANCELLED, INTERRUPTED),
            COMPLETED, EnumSet.noneOf(ResearchState.class),
            INTERRUPTED, EnumSet.of(RESEARCHING, VERIFYING, SYNTHESIZING, REVIEWING,
                    CANCELLED, FAILED),
            FAILED, EnumSet.noneOf(ResearchState.class),
            CANCELLED, EnumSet.noneOf(ResearchState.class));

    private ResearchStateMachine() {
    }

    public static boolean puedeTransicionar(ResearchState desde, ResearchState hacia) {
        return TRANSICIONES.getOrDefault(desde, Set.of()).contains(hacia);
    }

    /**
     * Lanza excepcion si la transicion no es valida.
     *
     * <p>Fallar es lo correcto: una transicion invalida significa que hay un
     * bug en el orquestador, y si se permitiera, la investigacion continuaria
     * en un estado incoherente que nadie podria diagnosticar mas tarde.
     */
    public static void exigir(ResearchState desde, ResearchState hacia) {
        if (!puedeTransicionar(desde, hacia)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Transicion invalida: " + desde + " -> " + hacia
                            + ". Permitidas: " + TRANSICIONES.getOrDefault(desde, Set.of()));
        }
    }

    public static Set<ResearchState> transicionesDesde(ResearchState desde) {
        return Set.copyOf(TRANSICIONES.getOrDefault(desde, Set.of()));
    }

    /**
     * Estados en los que el orquestador puede entrar sin haber pasado por
     * PLANNING, por ejemplo al reanudar una investigacion interrumpida.
     */
    public static boolean esEstadoDeTrabajo(ResearchState estado) {
        return estado == PLANNING || estado == RESEARCHING || estado == VERIFYING
                || estado == SYNTHESIZING || estado == REVIEWING;
    }
}
