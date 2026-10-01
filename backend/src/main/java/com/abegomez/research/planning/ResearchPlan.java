package com.abegomez.research.planning;

import java.util.List;

/**
 * Plan de investigacion generado por el Planner.
 *
 * <p>El plan es una propuesta, no una orden. Las tareas las ejecuta el
 * orquestador en el orden que elije, y ninguna se considera buena solo porque el
 * modelo la propuso: si al ejecutarla no produce evidencia, se descarta y el
 * propio agente decide si merece otra ronda.
 *
 * @param investigacionId  investigacion a la que pertenece
 * @param objetivo         objetivo normalizado
 * @param tareas           lineas de trabajo a ejecutar
 * @param preguntas        lo que el objetivo no aclara y hay que resolver
 */
public record ResearchPlan(
        Long investigacionId,
        String objetivo,
        List<PlanTask> tareas,
        List<String> preguntas) {

    public ResearchPlan {
        tareas = tareas == null ? List.of() : List.copyOf(tareas);
        preguntas = preguntas == null ? List.of() : List.copyOf(preguntas);
    }

    public boolean vacio() {
        return tareas.isEmpty();
    }

    /**
     * Una linea de trabajo del plan.
     *
     * @param descripcion que hay que determinar y con que criterio se da por hecho
     * @param tipoFuente  donde se buscara principalmente
     * @param prioridad   mayor numero, mayor prioridad
     * @param criterios   como se sabe que la tarea esta resuelta
     */
    public record PlanTask(String descripcion, TipoFuentePlan tipoFuente, int prioridad,
                           List<String> criterios) {

        public PlanTask {
            criterios = criterios == null ? List.of() : List.copyOf(criterios);
        }
    }

    /**
     * Tipo de fuente previsto para una tarea.
     *
     * <p>Se mapea a {@code tareas_investigacion.tipo_fuente}, que solo admite
     * INTERNA, WEB y BASE_DATOS. El enum existe para no repetir cadenas sueltas
     * y para que el mapeo a la base sea explicito.
     */
    public enum TipoFuentePlan {

        INTERNA,
        WEB,
        BASE_DATOS;

        public static TipoFuentePlan desde(String texto) {
            if (texto == null || texto.isBlank()) {
                return INTERNA;
            }
            try {
                return valueOf(texto.trim().toUpperCase(java.util.Locale.ROOT));
            }
            catch (IllegalArgumentException ex) {
                // Un modelo puede inventar un valor. Se cae al tipo por defecto
                // en lugar de rechazar el plan entero: perder una tarea por un
                // valor mal escrito seria peor que buscar en la fuente interna.
                return INTERNA;
            }
        }
    }
}
