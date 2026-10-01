package com.abegomez.research.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transiciones de estado, presupuesto y eventos de una investigacion.
 *
 * <p>Es el unico sitio por el que una investigacion cambia de estado. Ningun
 * agente modifica el estado: lo pide aqui, y aqui se decide. Esa separacion es
 * la que sostiene la regla de que el LLM decide contenido y el codigo controla
 * el flujo.
 *
 * <p>Cada transicion deja evento. Sin el, un fallo de la Fase 6 seria
 * indiagnosticable: habria estado final sin explicacion de como se llego.
 */
@Service
public class ResearchManager {

    private static final Logger log = LoggerFactory.getLogger(ResearchManager.class);

    /** Umbral de aviso de presupuesto, en fraccion consumida. */
    private static final double UMBRAL_AVISO_PRESUPUESTO = 0.8;

    private final ResearchRepository repository;
    private final ResearchEventRepository eventos;
    private final ObjectMapper objectMapper;

    /**
     * Investigative ids de los que ya se emitio el aviso de presupuesto.
     *
     * <p>Solo en memoria y a proposito: el aviso es una comodidad de
     * observabilidad, no una garantia. Si tras un reinicio se repitiera, el
     * coste es un evento de mas en la linea de tiempo.
     */
    private final java.util.Set<Long> avisoPresupuestoEmitido =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ResearchManager(ResearchRepository repository, ResearchEventRepository eventos,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.eventos = eventos;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public long crear(String objetivo, long presupuestoTokens, int maxRondas) {
        long id = repository.crear(objetivo, presupuestoTokens, maxRondas, null);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("investigacionId", id);
        payload.put("objetivo", objetivo);
        payload.put("presupuestoTokens", presupuestoTokens);
        payload.put("maxRondas", maxRondas);

        eventos.registrar(id, ResearchEventType.INVESTIGATION_CREATED, payload);

        log.info("Investigacion {} creada: presupuesto={} tokens, maxRondas={}",
                id, presupuestoTokens, maxRondas);
        return id;
    }

    public ResearchRepository.EstadoInvestigacion obtener(long investigacionId) {
        return repository.find(investigacionId)
                .orElseThrow(() -> new com.abegomez.research.common.exception.BusinessException(
                        com.abegomez.research.common.ErrorCode.NOT_FOUND,
                        "No existe la investigacion " + investigacionId));
    }

    public ResearchState estado(long investigacionId) {
        return obtener(investigacionId).estado();
    }

    /**
     * Cambia el estado validando la transicion y dejando evento.
     *
     * <p>Se relee el estado en lugar de recibirlo del llamador: si el llamador
     * dijera el estado de origen, la validacion de la maquina no serviria para
     * nada.
     */
    @Transactional
    public void transicionar(long investigacionId, ResearchState hacia) {
        transicionar(investigacionId, hacia, null);
    }

    @Transactional
    public void transicionar(long investigacionId, ResearchState hacia, String motivo) {
        var actual = obtener(investigacionId);

        if (actual.estado() == hacia) {
            return;
        }

        repository.transicionar(investigacionId, actual.estado(), hacia, motivo);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("desde", actual.estado().name());
        payload.put("hacia", hacia.name());
        if (motivo != null) {
            payload.put("motivo", motivo);
        }
        eventos.registrar(investigacionId, ResearchEventType.STATE_CHANGED, payload);

        log.info("Investigacion {}: {} -> {}", investigacionId, actual.estado(), hacia);
    }

    /**
     * Descuenta tokens y avisa cuando el presupuesto esta cerca de agotarse.
     *
     * @return true si todavia queda margen
     */
    @Transactional
    public boolean descontarTokens(long investigacionId, long tokens) {
        boolean queda = repository.descontarTokens(investigacionId, tokens);

        if (!queda) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("tokensSolicitados", tokens);
            payload.put("motivo", "presupuesto insuficiente");
            eventos.registrar(investigacionId, ResearchEventType.BUDGET_LOW, payload);
            log.warn("Investigacion {}: presupuesto insuficiente para {} tokens", investigacionId, tokens);
            return false;
        }

        var estado = obtener(investigacionId);
        if (estado.presupuesto().porcentajeConsumido() >= UMBRAL_AVISO_PRESUPUESTO
                && !avisoPresupuestoEmitido.contains(investigacionId)) {
            avisoPresupuestoEmitido.add(investigacionId);

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("tokensConsumidos", estado.tokensConsumidos());
            payload.put("presupuestoTokens", estado.presupuestoTokens());
            payload.put("porcentaje",
                    Math.round(estado.presupuesto().porcentajeConsumido() * 100));
            eventos.registrar(investigacionId, ResearchEventType.BUDGET_LOW, payload);

            log.warn("Investigacion {}: presupuesto al {}%", investigacionId,
                    Math.round(estado.presupuesto().porcentajeConsumido() * 100));
        }

        return true;
    }

    /**
     * Maximo de tokens que puede gastar la siguiente llamada.
     */
    public long maximoSiguienteLlamada(long investigacionId) {
        return obtener(investigacionId).presupuesto().maximoSiguienteLlamada();
    }

    public boolean presupuestoAgotado(long investigacionId) {
        return obtener(investigacionId).presupuestoAgotado();
    }

    public boolean rondasAgotadas(long investigacionId) {
        return obtener(investigacionId).rondasAgotadas();
    }

    @Transactional
    public void avanzarRonda(long investigacionId) {
        var antes = obtener(investigacionId);
        repository.avanzarRonda(investigacionId);
        var despues = obtener(investigacionId);

        if (despues.rondaActual() != antes.rondaActual()) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("ronda", despues.rondaActual());
            payload.put("maxRondas", despues.maxRondas());
            payload.put("estadoSiguiente", ResearchState.RESEARCHING.name());
            // Evento propio, no CHECKPOINT_SAVED: un consumidor que reinicie
            // por un checkpoint no debe confundir el avance de ronda con una
            // reanudacion.
            eventos.registrar(investigacionId, ResearchEventType.ROUND_STARTED, payload);

            if (despues.rondasAgotadas()) {
                ObjectNode aviso = objectMapper.createObjectNode();
                aviso.put("ronda", despues.rondaActual());
                aviso.put("maxRondas", despues.maxRondas());
                eventos.registrar(investigacionId, ResearchEventType.ROUNDS_EXHAUSTED, aviso);
            }
        }
    }

    /**
     * Publica un evento de dominio.
     *
     * <p>Los agentes pasan por aqui en lugar de escribir en la tabla: asi el
     * formato del payload y el nombre del evento quedan en un unico sitio.
     */
    public void evento(long investigacionId, ResearchEventType tipo,
                       com.fasterxml.jackson.databind.JsonNode payload) {
        eventos.registrar(investigacionId, tipo, payload);
    }

    /**
     * Marca la investigacion como fallida con su motivo.
     */
    @Transactional
    public void fallar(long investigacionId, String motivo) {
        var actual = obtener(investigacionId);
        if (actual.estado().isTerminal()) {
            return;
        }
        transicionar(investigacionId, ResearchState.FAILED, motivo);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("motivo", motivo);
        eventos.registrar(investigacionId, ResearchEventType.FAILURE, payload);
    }

    public ResearchEventRepository eventosDe(long investigacionId) {
        return eventos;
    }
}
