package com.abegomez.research.tools.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests de la proteccion SSRF.
 *
 * <p>El caso que mas importa es el de resolucion mixta: un host con un registro
 * A publico y un AAAA interno. Es el bypass clasico de los validadores que solo
 * miran la cadena de la URL.
 */
class SsrfGuardTest {

    /** Resolucion controlada, para probar sin red. */
    private static SsrfGuard.Resolver resuelve(String... ips) {
        return host -> {
            InetAddress[] direcciones = new InetAddress[ips.length];
            for (int i = 0; i < ips.length; i++) {
                direcciones[i] = InetAddress.getByName(ips[i]);
            }
            return direcciones;
        };
    }

    @Test
    @DisplayName("Acepta un host publico")
    void aceptaHostPublico() throws UnknownHostException {
        SsrfGuard.Resultado resultado =
                SsrfGuard.validar("https://example.com/articulo", resuelve("93.184.216.34"));

        assertTrue(resultado.permitido());
        assertTrue(resultado.urlNormalizada().startsWith("https://example.com"));
    }

    @Test
    @DisplayName("Rechaza loopback escrito directamente")
    void rechazaLoopbackLiteral() throws UnknownHostException {
        assertFalse(SsrfGuard.validar("http://127.0.0.1:8081/", resuelve("127.0.0.1")).permitido());
        assertFalse(SsrfGuard.validar("http://localhost:8081/", resuelve("127.0.0.1")).permitido());
        assertFalse(SsrfGuard.validar("http://[::1]/", resuelve("::1")).permitido());
    }

    @Test
    @DisplayName("Rechaza un host publico que resuelve a una IP privada")
    void rechazaResolucionPrivada() throws UnknownHostException {
        // El nombre parece legitimo; lo peligroso es donde acaba apuntando.
        assertFalse(SsrfGuard.validar("https://ejemplo.com/", resuelve("10.0.0.5")).permitido());
        assertFalse(SsrfGuard.validar("https://ejemplo.com/", resuelve("192.168.1.1")).permitido());
        assertFalse(SsrfGuard.validar("https://ejemplo.com/", resuelve("172.16.0.1")).permitido());
    }

    @Test
    @DisplayName("Rechaza una resolucion mixta con una sola direccion interna")
    void rechazaResolucionMixta() throws UnknownHostException {
        // Bypass clasico: un A publico convive con un AAAA a loopback.
        assertFalse(SsrfGuard.validar("https://ejemplo.com/",
                resuelve("93.184.216.34", "127.0.0.1")).permitido());
        assertFalse(SsrfGuard.validar("https://ejemplo.com/",
                resuelve("93.184.216.34", "169.254.169.254")).permitido());
    }

    @Test
    @DisplayName("Rechaza la IP de metadatos de nube")
    void rechazaIpDeMetadatos() throws UnknownHostException {
        assertFalse(SsrfGuard.validar("http://169.254.169.254/latest/meta-data/",
                resuelve("169.254.169.254")).permitido());
    }

    @Test
    @DisplayName("Rechaza esquemas distintos de http y https")
    void rechazaEsquemasNoPermitidos() {
        SsrfGuard.Resolver publico = resuelve("93.184.216.34");

        assertFalse(SsrfGuard.validar("file:///etc/passwd", publico).permitido());
        assertFalse(SsrfGuard.validar("ftp://example.com/", publico).permitido());
        assertFalse(SsrfGuard.validar("gopher://example.com/", publico).permitido());
    }

    @Test
    @DisplayName("Rechaza puertos no estandar")
    void rechazaPuertosNoPermitidos() {
        SsrfGuard.Resolver publico = resuelve("93.184.216.34");

        assertTrue(SsrfGuard.validar("https://example.com/", publico).permitido());
        assertFalse(SsrfGuard.validar("http://example.com:22/", publico).permitido());
        assertFalse(SsrfGuard.validar("http://example.com:6379/", publico).permitido());
        assertFalse(SsrfGuard.validar("http://example.com:5432/", publico).permitido());
    }

    @Test
    @DisplayName("Rechaza credenciales embebidas en la URL")
    void rechazaCredenciales() {
        assertFalse(SsrfGuard.validar("https://usuario:clave@example.com/",
                resuelve("93.184.216.34")).permitido());
    }

    @Test
    @DisplayName("Rechaza un host que no resuelve")
    void rechazaHostInexistente() {
        SsrfGuard.Resolver falla = host -> {
            throw new UnknownHostException(host);
        };

        assertFalse(SsrfGuard.validar("https://no-existe.example/", falla).permitido());
    }

    @ParameterizedTest(name = "rechaza la IP reservada {0}")
    @ValueSource(strings = {"0.0.0.0", "100.64.0.1", "198.18.0.1", "203.0.113.5", "224.0.0.1"})
    @DisplayName("Rechaza los rangos reservados que no cubre isSiteLocalAddress")
    void rechazaRangosReservados(String ip) throws UnknownHostException {
        assertFalse(SsrfGuard.validar("http://" + ip + "/", resuelve(ip)).permitido());
    }
}