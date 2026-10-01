package com.abegomez.research.tools.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Proteccion contra SSRF para las tools que descargan URLs.
 *
 * <p>El fallo clasico de este control es validar el texto de la URL y dejar que
 * la resolucion de DNS haga otra cosa. Por eso aqui se resuelve el host y se
 * comprueban **todas** las direcciones devueltas: un unico A record publico con
 * un AAAA record en loopback sigue siendo un bypass.
 *
 * <p>Tambien se rechazan los puertos no estandar, porque una tool de lectura de
 * articulos no necesita hablar por el 22, el 25 o el 6379, y permitirlos solo
 * amplia lo que un atacante puede alcanzar.
 */
public final class SsrfGuard {

    /** Resolucion de DNS, inyectable para poder probar sin red. */
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Puertos que un cliente HTTP legitimo usaria para leer una pagina. */
    private static final Set<Integer> PUERTOS_PERMITIDOS = Set.of(80, 443, 8080, 8443);

    private static final Set<String> ESQUEMAS_PERMITIDOS = Set.of("http", "https");

    private SsrfGuard() {
    }

    /**
     * @param permitido         si la URL puede solicitarse
     * @param urlNormalizada    URL verificada, tal como debe usarse
     * @param motivo            por que se rechaza
     */
    public record Resultado(boolean permitido, String urlNormalizada, String motivo) {

        static Resultado ok(String url) {
            return new Resultado(true, url, null);
        }

        static Resultado no(String motivo) {
            return new Resultado(false, null, motivo);
        }
    }

    public static Resultado validar(String url, Resolver resolver) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        }
        catch (IllegalArgumentException ex) {
            return Resultado.no("la URL no es valida");
        }

        String esquema = uri.getScheme();
        if (esquema == null || !ESQUEMAS_PERMITIDOS.contains(esquema.toLowerCase(Locale.ROOT))) {
            return Resultado.no("solo se admiten los esquemas http y https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Resultado.no("la URL no indica un host");
        }

        int puerto = uri.getPort();
        if (puerto != -1 && !PUERTOS_PERMITIDOS.contains(puerto)) {
            return Resultado.no("el puerto " + puerto + " no esta permitido; usa 80, 443, 8080 o 8443");
        }

        // Credenciales embebidas sirven para confundir al usuario sobre el destino
        // real. No hay ningun uso legitimo para el agente.
        if (uri.getUserInfo() != null) {
            return Resultado.no("la URL no puede contener credenciales");
        }

        if (esHostLiteral(host)) {
            Resultado literal = comprobarLiterales(host);
            if (!literal.permitido()) {
                return literal;
            }
            return Resultado.ok(uri.toString());
        }

        InetAddress[] direcciones;
        try {
            direcciones = resolver.resolve(host);
        }
        catch (UnknownHostException ex) {
            return Resultado.no("el host '" + host + "' no se pudo resolver");
        }
        if (direcciones == null || direcciones.length == 0) {
            return Resultado.no("el host '" + host + "' no resolvio a ninguna direccion");
        }

        for (InetAddress direccion : direcciones) {
            if (esProhibida(direccion)) {
                return Resultado.no("el host '" + host + "' resuelve a una direccion interna ("
                        + direccion.getHostAddress() + ")");
            }
        }

        return Resultado.ok(uri.toString());
    }

    private static boolean esHostLiteral(String host) {
        String normalizado = host.toLowerCase(Locale.ROOT);
        return normalizado.equals("localhost") || normalizado.endsWith(".localhost");
    }

    private static Resultado comprobarLiterales(String host) {
        String normalizado = host.toLowerCase(Locale.ROOT);

        // URI.getHost() devuelve las IPv6 entre corchetes: "[::1]".
        if (normalizado.startsWith("[") && normalizado.endsWith("]")) {
            normalizado = normalizado.substring(1, normalizado.length() - 1);
        }

        if (normalizado.equals("localhost") || normalizado.endsWith(".localhost")) {
            return Resultado.no("no se permite acceder al host local");
        }

        // Se intenta parsear como IP: cubre 127.0.0.1 y ::1 escritos a mano,
        // que no pasan por el resolver.
        try {
            InetAddress[] literales = InetAddress.getAllByName(normalizado);
            for (InetAddress direccion : literales) {
                if (esProhibida(direccion)) {
                    return Resultado.no("la direccion " + normalizado + " es interna o reservada");
                }
            }
        }
        catch (UnknownHostException ex) {
            return Resultado.no("el host '" + host + "' no es valido");
        }
        return Resultado.ok(null);
    }

    /**
     * Comprueba las categorias de direcciones que nunca deben ser alcanzables
     * desde una tool que consume entrada de un modelo.
     */
    static boolean esProhibida(InetAddress direccion) {
        if (direccion.isAnyLocalAddress() || direccion.isLoopbackAddress()
                || direccion.isLinkLocalAddress() || direccion.isSiteLocalAddress()
                || direccion.isMulticastAddress()) {
            return true;
        }

        byte[] octetos = direccion.getAddress();
        if (octetos.length != 4) {
            // IPv6 unico local (fc00::/7), que isSiteLocalAddress cubre en JDK 17+,
            // y link local ya cubierto arriba. Se comprueba el prefijo de forma
            // explicita para no depender de la version del JDK.
            int byte0 = octetos[0] & 0xff;
            return (byte0 & 0xfe) == 0xfc || byte0 == 0xfe || byte0 == 0x02;
        }

        int primero = octetos[0] & 0xff;
        int segundo = octetos[1] & 0xff;

        // 0.0.0.0/8, 100.64.0.0/10 (CGNAT), 169.254.0.0/16, 192.0.0.0/24,
        // 192.0.2.0/24, 198.18.0.0/15, 198.51.100.0/24, 203.0.113.0/24,
        // 224.0.0.0/4 y 240.0.0.0/4 (reservado).
        if (primero == 0 || primero == 127 || primero >= 224) {
            return true;
        }
        if (primero == 100 && segundo >= 64 && segundo <= 127) {
            return true;
        }
        if (primero == 169 && segundo == 254) {
            return true;
        }
        if (primero == 192 && segundo == 0 && (octetos[2] & 0xff) == 0) {
            return true;
        }
        if (primero == 192 && segundo == 0 && (octetos[2] & 0xff) == 2) {
            return true;
        }
        if (primero == 198 && (segundo == 18 || segundo == 19)) {
            return true;
        }
        if (primero == 198 && segundo == 51 && (octetos[2] & 0xff) == 100) {
            return true;
        }
        if (primero == 203 && segundo == 0 && (octetos[2] & 0xff) == 113) {
            return true;
        }
        // 172.16.0.0/12
        return primero == 172 && segundo >= 16 && segundo <= 31;
    }
}