package com.abegomez.research.tools.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests del validador de SQL.
 *
 * <p>Se prueban las entradas que un modelo podria generar, no solo las
 * canonicas. Un guard que solo bloquea el caso obvio no aporta nada: el modelo
 * escribe {@code SeLeCt}, con espacios raros y mayusculas alternas.
 */
class SqlQueryGuardTest {

    @Test
    @DisplayName("Una consulta de solo lectura se acepta y recibe el limite del sistema")
    void aceptaConsultaValida() {
        SqlQueryGuard.Resultado resultado =
                SqlQueryGuard.validar("SELECT pais, total FROM demo_transacciones");

        assertTrue(resultado.permitido());
        assertTrue(resultado.consultaSql().endsWith("LIMIT " + SqlQueryGuard.maxFilas()));
    }

    @Test
    @DisplayName("Un LIMIT escrito por el modelo se sobreescribe con el maximo permitido")
    void sobreescribeLimiteExistente() {
        SqlQueryGuard.Resultado resultado =
                SqlQueryGuard.validar("SELECT * FROM demo_incidentes LIMIT 999999");

        assertTrue(resultado.permitido());
        assertFalse(resultado.consultaSql().contains("999999"));
        assertTrue(resultado.consultaSql().endsWith("LIMIT " + SqlQueryGuard.maxFilas()));
    }

    @ParameterizedTest(name = "rechaza: {0}")
    @ValueSource(strings = {
            "DELETE FROM demo_transacciones",
            "UPDATE demo_transacciones SET total = 0",
            "DROP TABLE demo_transacciones",
            "INSERT INTO demo_tecnologias VALUES (1, 'x')",
            "TRUNCATE demo_incidentes",
            "ALTER TABLE demo_tecnologias ADD COLUMN x int",
            "GRANT ALL ON demo_transacciones TO public"
    })
    @DisplayName("Rechaza cualquier sentencia que no sea un SELECT")
    void rechazaEscritura(String sql) {
        assertFalse(SqlQueryGuard.validar(sql).permitido());
    }

    @Test
    @DisplayName("Rechaza UNION, que permite encadenar consultas")
    void rechazaUnion() {
        SqlQueryGuard.Resultado resultado = SqlQueryGuard.validar(
                "SELECT pais FROM demo_transacciones UNION SELECT nombre FROM demo_tecnologias");

        assertFalse(resultado.permitido());
        assertTrue(resultado.motivo().toLowerCase(Locale.ROOT).contains("union"));
    }

    @Test
    @DisplayName("Rechaza una tabla fuera de la lista permitida")
    void rechazaTablaNoPermitida() {
        SqlQueryGuard.Resultado resultado = SqlQueryGuard.validar("SELECT * FROM usuarios");

        assertFalse(resultado.permitido());
        assertTrue(resultado.motivo().contains("no esta permitida"));
    }

    @Test
    @DisplayName("Rechaza lecturas de tablas del sistema")
    void rechazaTablasDelSistema() {
        assertFalse(SqlQueryGuard.validar("SELECT * FROM pg_shadow").permitido());
        assertFalse(SqlQueryGuard.validar("SELECT * FROM pg_stat_activity").permitido());
    }

    @Test
    @DisplayName("Rechaza funciones que permiten leer archivos o esperar")
    void rechazaFuncionesPeligrosas() {
        assertFalse(SqlQueryGuard.validar(
                "SELECT pg_read_file('/etc/passwd') FROM demo_transacciones").permitido());
        assertFalse(SqlQueryGuard.validar(
                "SELECT pg_sleep(10) FROM demo_transacciones").permitido());
    }

    @Test
    @DisplayName("Rechaza comentarios, que ocultan codigo ejecutable")
    void rechazaComentarios() {
        assertFalse(SqlQueryGuard.validar("SELECT 1 -- ; DROP TABLE x").permitido());
        assertFalse(SqlQueryGuard.validar("SELECT 1 /* otro */ FROM demo_transacciones").permitido());
    }

    @Test
    @DisplayName("Rechaza comillas triples, via clasica de inyeccion en funciones dollar-quoted")
    void rechazaComillasTriples() {
        assertFalse(SqlQueryGuard.validar("SELECT $$ malicioso $$ FROM demo_transacciones").permitido());
    }

    @Test
    @DisplayName("Ignora lo que venga despues del primer punto y coma")
    void recortaEnPrimerPuntoYcoma() {
        SqlQueryGuard.Resultado resultado = SqlQueryGuard.validar(
                "SELECT pais FROM demo_transacciones; DROP TABLE demo_incidentes");

        assertTrue(resultado.permitido(), "solo debe ejecutarse la parte previa al primer punto y coma");
        assertFalse(resultado.consultaSql().contains("DROP"));
    }

    @Test
    @DisplayName("Acepta las variantes de mayusculas que genera un modelo")
    void aceptaVariantesDeMayusculas() {
        assertTrue(SqlQueryGuard.validar("sElEcT pais FROM demo_transacciones").permitido());
    }

    @Test
    @DisplayName("Acepta las seis tablas del dataset de demostracion")
    void aceptaTodasLasTablasDemo() {
        for (String tabla : SqlQueryGuard.tablasPermitidas()) {
            assertTrue(SqlQueryGuard.validar("SELECT * FROM " + tabla).permitido(),
                    "deberia permitir la tabla " + tabla);
        }
        assertEquals(6, SqlQueryGuard.tablasPermitidas().size());
    }

    @Test
    @DisplayName("Rechaza entradas vacias o nulas")
    void rechazaVacias() {
        assertFalse(SqlQueryGuard.validar(null).permitido());
        assertFalse(SqlQueryGuard.validar("").permitido());
        assertFalse(SqlQueryGuard.validar("   ").permitido());
    }
}