package com.abegomez.research;

import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que la migracion V1 se aplica completa sobre PostgreSQL con pgvector:
 * extension disponible, tablas creadas y restricciones de estado presentes.
 *
 * <p>Requiere Docker. Si Docker no esta disponible, el test se omite.
 */
@SpringBootTest(classes = {ResearchPlatformApplication.class, TestBeansConfiguration.class,
        PostgresContainerConfiguration.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIT {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("La extension pgvector queda instalada")
    void pgvectorExtensionIsInstalled() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT extversion FROM pg_extension WHERE extname = 'vector'");
             var resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("extversion")).isNotBlank();
        }
    }

    @Test
    @DisplayName("Todas las tablas del esquema base existen")
    void allTablesExist() throws Exception {
        var esperadas = java.util.List.of(
                "investigaciones", "tareas_investigacion", "documentos", "fragmentos_documento",
                "fuentes", "evidencias", "hallazgos", "hallazgo_evidencia", "contradicciones",
                "preguntas_abiertas", "informes", "ejecuciones_agente", "ejecuciones_herramienta",
                "eventos_investigacion");

        Map<String, String> porDefecto = query(
                "SELECT table_name, table_type FROM information_schema.tables "
                        + "WHERE table_schema = 'public'");

        assertThat(porDefecto.keySet()).containsAll(esperadas);
        assertThat(porDefecto.get("investigaciones")).isEqualTo("BASE TABLE");
    }

    @Test
    @DisplayName("El esquema de embeddings acepta el vector de 768 dimensiones")
    void embeddingColumnAcceptsConfiguredDimension() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO documentos (nombre, tipo, contenido_texto, hash) VALUES (?, 'TXT', ?, ?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "documento de prueba");
            statement.setString(2, "contenido");
            statement.setString(3, "hash-de-prueba");
            statement.executeUpdate();

            try (var keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                long documentoId = keys.getLong(1);

                try (var fragmento = connection.prepareStatement(
                        "INSERT INTO fragmentos_documento (documento_id, indice, texto, embedding) "
                                + "VALUES (?, 0, ?, ?::vector)")) {
                    fragmento.setLong(1, documentoId);
                    fragmento.setString(2, "fragmento");
                    fragmento.setString(3, zeroVector(768));
                    assertThat(fragmento.executeUpdate()).isEqualTo(1);
                }

                try (var busqueda = connection.prepareStatement(
                        "SELECT texto FROM fragmentos_documento "
                                + "WHERE documento_id = ? ORDER BY embedding <=> ?::vector LIMIT 1")) {
                    busqueda.setLong(1, documentoId);
                    busqueda.setString(2, zeroVector(768));
                    try (var resultSet = busqueda.executeQuery()) {
                        assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getString("texto")).isEqualTo("fragmento");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("La tabla investigaciones rechaza estados fuera del catalogo")
    void estadoIsConstrained() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO investigaciones (objetivo, estado) VALUES ('objetivo', ?)")) {
            statement.setString(1, "EXPLORANDO");
            assertThat(statement.executeUpdate()).isNegative();
        }
        catch (java.sql.SQLException ex) {
            assertThat(ex.getSQLState()).isEqualTo("23514");
        }
    }

    @Test
    @DisplayName("Flyway registro la migracion aplicada y no hay cambios pendientes")
    void flywayIsClean() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT version, description, success FROM flyway_schema_history "
                             + "WHERE version = '1'");
             var resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("description")).contains("esquema base");
            assertThat(resultSet.getBoolean("success")).isTrue();
        }
    }

    private Map<String, String> query(String sql) throws Exception {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                result.put(resultSet.getString(1), resultSet.getString(2));
            }
        }
        return result;
    }

    private static String zeroVector(int dimension) {
        return "[" + "0".repeat(dimension - 1) + ",1]";
    }
}
