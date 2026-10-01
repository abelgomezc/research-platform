package com.abegomez.research;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Contenedor de PostgreSQL con la extension pgvector para los tests de
 * integracion. Spring Boot arranca y detiene el contenedor por su cuenta.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
                .withDatabaseName("research_platform_test")
                .withUsername("test")
                .withPassword("test");
    }
}
