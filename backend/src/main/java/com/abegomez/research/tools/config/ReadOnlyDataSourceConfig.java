package com.abegomez.research.tools.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Conexion de solo lectura para la tool {@code query_database}.
 *
 * <p>El validador de SQL ({@code SqlQueryGuard}) reduce superficie, pero la
 * garantia real de que una tool de consulta no escriba la da PostgreSQL: el
 * usuario {@code research_ro} solo tiene permisos de SELECT. Si el validador
 * tuviera un hueco hoy o en el futuro, la escritura seguiria siendo imposible.
 *
 * <p>Por eso esta configuracion es separada del {@code DataSource} principal. El
 * resto de la aplicacion, incluida la ingesta de documentos y el registro de
 * evidencias, sigue usando la conexion con permisos de escritura; solo las
 * consultas del agente pasan por aqui.
 */
@Configuration
public class ReadOnlyDataSourceConfig {

    /**
     * Propiedades del pool de solo lectura, bajo {@code app.read-only-datasource.*}.
     *
     * <p>Si no se declaran credenciales propias, se hereda URL y usuario del
     * pool principal cambiandolo a un usuario sin permisos de escritura.
     */
    @ConfigurationProperties(prefix = "app.read-only-datasource")
    public static class PropiedadesReadOnly {

        private String url;
        private String username;
        private String password;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * Pool de solo lectura.
     *
     * <p>Se marca como no primario a proposito: el {@code DataSource} que inyecta
     * Spring Boot por defecto, y por tanto las operaciones de escritura, siguen
     * siendo las de la aplicacion.
     */
    @Bean(name = "readOnlyDataSource")
    HikariDataSource readOnlyDataSource(DataSourceProperties propiedadesPrincipales,
                                        PropiedadesReadOnly propiedades) {
        String url = tieneValor(propiedades.getUrl())
                ? propiedades.getUrl()
                : propiedadesPrincipales.getUrl();

        String usuario = tieneValor(propiedades.getUsername())
                ? propiedades.getUsername()
                : "research_ro";

        String contrasena = tieneValor(propiedades.getPassword())
                ? propiedades.getPassword()
                : "solo_lectura_demo";

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(usuario);
        dataSource.setPassword(contrasena);
        dataSource.setPoolName("read-only-pool");
        dataSource.setMaximumPoolSize(5);
        dataSource.setConnectionTimeout(10000);

        // El pool se cierra al apagar la aplicacion.
        dataSource.setAutoCommit(true);

        return dataSource;
    }

    /**
     * Gestor de transacciones del pool de solo lectura.
     *
     * <p>Se declara aparte del principal porque {@code SET LOCAL} debe aplicarse a
     * la conexion que ejecuta la consulta del agente, no a otra del pool.
     */
    @Bean(name = "readOnlyTransactionManager")
    PlatformTransactionManager readOnlyTransactionManager(
            @org.springframework.beans.factory.annotation.Qualifier("readOnlyDataSource")
            DataSource readOnlyDataSource) {
        return new DataSourceTransactionManager(readOnlyDataSource);
    }

    /**
     * Plantilla JDBC de solo lectura.
     *
     * <p>Es la que inyecta {@code QueryDatabaseTool}. Al estar marcada como
     * primaria seria incorrecto, porque el resto del codigo debe seguir viendo el
     * pool con permisos de escritura.
     */
    @Bean(name = "readOnlyJdbcTemplate")
    JdbcTemplate readOnlyJdbcTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("readOnlyDataSource")
            DataSource readOnlyDataSource) {
        return new JdbcTemplate(readOnlyDataSource);
    }

    private static boolean tieneValor(String valor) {
        return valor != null && !valor.isBlank();
    }
}
