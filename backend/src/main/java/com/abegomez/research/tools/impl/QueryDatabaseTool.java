package com.abegomez.research.tools.impl;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;

import com.abegomez.research.tools.ResearchTool;
import com.abegomez.research.tools.RiskLevel;
import com.abegomez.research.tools.ToolContext;
import com.abegomez.research.tools.ToolDefinition;
import com.abegomez.research.tools.ToolPermission;
import com.abegomez.research.tools.ToolResult;
import com.abegomez.research.tools.security.SqlQueryGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.NestedRuntimeException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ejecuta una consulta SQL de solo lectura sobre el dataset de demostracion.
 *
 * <p>Es la tool con mas superficie de ataque de todas, asi que aplica defensa en
 * capas: valida la consulta con {@link SqlQueryGuard} y, sobre todo, corre con un
 * usuario de base de datos de solo lectura. Si el validador tuviera un hueco, la
 * base de datos seguiria rechazando la escritura.
 *
 * <p>El statement timeout se antepone a la sentencia en la misma transaccion, de
 * modo que sigue vigente aunque la consulta llegue a la base de datos por otra
 * via.
 */
@Component
public class QueryDatabaseTool implements ResearchTool {

    private static final int TIMEOUT_SEGUNDOS = 5;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * El pool y el gestor de transacciones son los de solo lectura: la garantia
     * de que esta tool no escribe esta en las credenciales, no en el validador.
     */
    public QueryDatabaseTool(
            @org.springframework.beans.factory.annotation.Qualifier("readOnlyJdbcTemplate")
            JdbcTemplate jdbcTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("readOnlyTransactionManager")
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition.Builder(
                ToolDefinition.Names.QUERY_DATABASE,
                "Ejecuta una consulta SQL de solo lectura sobre el dataset de demostracion. "
                        + "Solo se admite un unico SELECT sobre las tablas permitidas, y el limite "
                        + "de filas lo impone el sistema. "
                        + "Tablas: " + String.join(", ", SqlQueryGuard.tablasPermitidas()) + ". "
                        + "Usala para cifras agregadas y conteos exactos, no para lectura de texto.",
                """
                {
                  "type": "object",
                  "properties": {
                    "sql": {
                      "type": "string",
                      "description": "Consulta SELECT. No incluyas LIMIT ni punto y coma final: el sistema los aplica."
                    },
                    "descripcion": {
                      "type": "string",
                      "description": "Que se busca obtener con la consulta. Se usa para trazabilidad."
                    }
                  },
                  "required": ["sql"]
                }
                """,
                """
                {
                  "type": "object",
                  "properties": {
                    "columnas": {
                      "type": "array",
                      "items": {"type": "string"}
                    },
                    "filas": {
                      "type": "array",
                      "items": {
                        "type": "object"
                      }
                    },
                    "totalFilas": {"type": "integer"}
                  }
                }
                """)
                .permission(ToolPermission.READ)
                .riskLevel(RiskLevel.LOW)
                .timeout(Duration.ofSeconds(15))
                .retries(1)
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode parametros) {
        String sql = parametros.path("sql").asText("").trim();

        SqlQueryGuard.Resultado validacion = SqlQueryGuard.validar(sql);
        if (!validacion.permitido()) {
            return ToolResult.fallo(
                    "Consulta rechazada: " + validacion.motivo()
                            + ". Reescribe la consulta como un unico SELECT sobre las tablas permitidas.",
                    Duration.ZERO, 1);
        }

        // SET LOCAL solo es valido dentro de una transaccion, asi que se envuelve
        // la consulta en una explicita. Ademas, sin transaccion, el timeout se
        // perderia al devolver la conexion al pool.
        String timeout = "SET LOCAL statement_timeout = " + (TIMEOUT_SEGUNDOS * 1000);

        try {
            ResultadoConsulta resultado = transactionTemplate.execute(estado -> {
                jdbcTemplate.execute(timeout);
                return jdbcTemplate.query(validacion.consultaSql(),
                        (ResultSetExtractor<ResultadoConsulta>) rs -> leer(rs));
            });

            if (resultado == null) {
                return ToolResult.fallo("La consulta no devolvio ningun conjunto de resultados",
                        Duration.ZERO, 1);
            }

            if (resultado.total() == 0) {
                return ToolResult.ok(
                        "La consulta se ejecuto correctamente y no devolvio filas.\n"
                                + resultado.columnas(),
                        Duration.ZERO, 1);
            }

            String aviso = resultado.total() >= SqlQueryGuard.maxFilas()
                    ? "\n[Resultado truncado en " + SqlQueryGuard.maxFilas() + " filas. "
                            + "Si necesitas el total exacto, usa COUNT(*) o agrupa mas.]"
                    : "";

            return ToolResult.ok(resultado.columnas() + resultado.filas() + aviso, Duration.ZERO, 1);
        }
        catch (Exception ex) {
            // El mensaje de PostgreSQL llega al agente porque ayuda a corregir la
            // consulta, pero sin detalle de conexion ni credenciales.
            Throwable causa = ex instanceof NestedRuntimeException nested
                    ? nested.getMostSpecificCause()
                    : ex;
            String mensaje = causa.getMessage();
            return ToolResult.fallo(
                    "La consulta fallo: " + (mensaje == null ? causa.getClass().getSimpleName() : mensaje),
                    Duration.ZERO, 1);
        }
    }

    /**
     * Vuelca el {@link ResultSet} como texto tabular legible.
     */
    private ResultadoConsulta leer(ResultSet rs) throws java.sql.SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnas = meta.getColumnCount();

        StringBuilder cabecera = new StringBuilder("Columnas: ");
        for (int i = 1; i <= columnas; i++) {
            cabecera.append(i > 1 ? ", " : "").append(meta.getColumnLabel(i));
        }

        StringBuilder cuerpo = new StringBuilder();
        int total = 0;
        while (rs.next()) {
            total++;
            if (total > SqlQueryGuard.maxFilas()) {
                break;
            }
            cuerpo.append("\nFila ").append(total).append(": ");
            for (int i = 1; i <= columnas; i++) {
                cuerpo.append(i > 1 ? " | " : "").append(rs.getString(i));
            }
        }
        return new ResultadoConsulta(cabecera.toString(), cuerpo.toString(), total);
    }

    private record ResultadoConsulta(String columnas, String filas, int total) {
    }
}