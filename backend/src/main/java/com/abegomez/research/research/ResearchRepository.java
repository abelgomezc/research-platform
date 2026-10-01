package com.abegomez.research.research;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a la tabla de investigaciones y a su presupuesto.
 *
 * <p>El presupuesto se descuenta con una operacion atomica
 * ({@code ... WHERE tokens_consumidos = ?}) y no con leer-modificar-escribir.
 * Sin esa condicion, dos tareas que investigan en paralelo leerian el mismo
 * saldo y ambas lo gastarian: el presupuesto es una garantia, y una garantia
 * que depende del orden de llegada no es una garantia.
 */
@Repository
public class ResearchRepository {

    private final JdbcTemplate jdbcTemplate;

    public ResearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Crea una investigacion en estado CREATED.
     */
    public long crear(String objetivo, long presupuestoTokens, int maxRondas, JsonNode configuracion) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO investigaciones
                    (objetivo, estado, ronda_actual, max_rondas, presupuesto_tokens,
                     tokens_consumidos, configuracion_json)
                VALUES (?, 'CREATED', 0, ?, ?, 0, ?)
                RETURNING id
                """, Long.class, objetivo, maxRondas, presupuestoTokens,
                configuracion == null ? null : configuracion.toString());

        return id;
    }

    /**
     * Estado actual.
     */
    public Optional<EstadoInvestigacion> find(long investigacionId) {
        List<EstadoInvestigacion> resultado = jdbcTemplate.query("""
                SELECT id, objetivo, estado, ronda_actual, max_rondas,
                       presupuesto_tokens, tokens_consumidos, motivo_fallo
                FROM investigaciones WHERE id = ?
                """, (rs, rowNum) -> new EstadoInvestigacion(
                        rs.getLong("id"),
                        rs.getString("objetivo"),
                        ResearchState.valueOf(rs.getString("estado")),
                        rs.getInt("ronda_actual"),
                        rs.getInt("max_rondas"),
                        rs.getLong("presupuesto_tokens"),
                        rs.getLong("tokens_consumidos"),
                        rs.getString("motivo_fallo")),
                investigacionId);

        return resultado.stream().findFirst();
    }

    /**
     * Cambia el estado, validando la transicion contra la maquina de estados.
     *
     * <p>La validacion se hace aqui y no solo en el orquestador: cualquier otro
     * llamador pasaria por alto la maquina, y entonces la base de datos
     * aceptaria un salto de estado imposible.
     */
    public void transicionar(long investigacionId, ResearchState desde, ResearchState hacia,
                             String motivoFallo) {
        ResearchStateMachine.exigir(desde, hacia);

        int actualizadas = jdbcTemplate.update("""
                UPDATE investigaciones
                SET estado = ?,
                    motivo_fallo = COALESCE(?, motivo_fallo),
                    finalizado_en = CASE WHEN ? THEN NOW() ELSE finalizado_en END,
                    actualizado_en = NOW()
                WHERE id = ? AND estado = ?
                """, hacia.name(), motivoFallo, hacia.isTerminal(), investigacionId, desde.name());

        if (actualizadas == 0) {
            // Solo puede ocurrir si otra hebra movio el estado a la vez. Se
            // relanza como conflicto en lugar de fingir que la transicion
            // funciono.
            throw new IllegalStateException("La investigacion " + investigacionId
                    + " ya no estaba en " + desde + "; otra transicion se adelanto");
        }
    }

    /**
     * Descuenta tokens de forma atomica.
     *
     * @return true si el descuento se aplico; false si se agotó el presupuesto
     */
    public boolean descontarTokens(long investigacionId, long tokens) {
        if (tokens <= 0) {
            return true;
        }
        int actualizadas = jdbcTemplate.update("""
                UPDATE investigaciones
                SET tokens_consumidos = tokens_consumidos + ?,
                    actualizado_en = NOW()
                WHERE id = ?
                  AND tokens_consumidos + ? <= presupuesto_tokens
                """, tokens, investigacionId, tokens);
        return actualizadas > 0;
    }

    /**
     * Avanza la ronda.
     */
    public void avanzarRonda(long investigacionId) {
        jdbcTemplate.update("""
                UPDATE investigaciones
                SET ronda_actual = LEAST(ronda_actual + 1, max_rondas),
                    actualizado_en = NOW()
                WHERE id = ? AND ronda_actual < max_rondas
                """, investigacionId);
    }

    public TokenBudget presupuesto(long investigacionId) {
        return find(investigacionId)
                .map(estado -> new TokenBudget(estado.presupuestoTokens(), estado.tokensConsumidos()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No existe la investigacion " + investigacionId));
    }

    /**
     * Listado paginado, para la pantalla de historial del frontend.
     */
    public List<EstadoInvestigacion> listar(int limite, int desplazamiento) {
        return jdbcTemplate.query("""
                SELECT id, objetivo, estado, ronda_actual, max_rondas,
                       presupuesto_tokens, tokens_consumidos, motivo_fallo
                FROM investigaciones
                ORDER BY creado_en DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new EstadoInvestigacion(
                        rs.getLong("id"),
                        rs.getString("objetivo"),
                        ResearchState.valueOf(rs.getString("estado")),
                        rs.getInt("ronda_actual"),
                        rs.getInt("max_rondas"),
                        rs.getLong("presupuesto_tokens"),
                        rs.getLong("tokens_consumidos"),
                        rs.getString("motivo_fallo")),
                limite, desplazamiento);
    }

    /**
     * @param presupuestoTokens  presupuesto total
     * @param tokensConsumidos   consumo acumulado
     * @param motivoFallo        por que fallo, si fallo
     */
    public record EstadoInvestigacion(
            long id,
            String objetivo,
            ResearchState estado,
            int rondaActual,
            int maxRondas,
            long presupuestoTokens,
            long tokensConsumidos,
            String motivoFallo) {

        public TokenBudget presupuesto() {
            return new TokenBudget(presupuestoTokens, tokensConsumidos);
        }

        public boolean presupuestoAgotado() {
            return presupuesto().agotado();
        }

        public boolean rondasAgotadas() {
            return rondaActual >= maxRondas;
        }
    }
}
