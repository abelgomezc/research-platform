package com.abegomez.research.knowledge.pgvector;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a las tablas de documentos y fragmentos con pgvector.
 *
 * <p>Usa {@link JdbcTemplate} y no el VectorStore de Spring AI a proposito: aqui
 * solo se necesita similitud coseno simple, y escribir la consulta deja el
 * calculo de distancia y el limite de resultados a la vista y en los tests.
 */
@Repository
public class DocumentChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserta un documento. Si el hash ya existe devuelve el id del existente,
     * de modo que reprocesar el mismo contenido no duplique nada.
     */
    public long saveDocument(String nombre, String tipo, String contenido, String hash) {
        Long existing = jdbcTemplate.queryForObject(
                "SELECT id FROM documentos WHERE hash = ?", Long.class, hash);
        if (existing != null) {
            return existing;
        }

        return jdbcTemplate.queryForObject(
                "INSERT INTO documentos (nombre, tipo, contenido_texto, hash) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, nombre, tipo, contenido, hash);
    }

    public boolean existsByHash(String hash) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documentos WHERE hash = ?", Integer.class, hash);
        return count != null && count > 0;
    }

    public Optional<String> findNombreById(long id) {
        List<String> nombres = jdbcTemplate.queryForList(
                "SELECT nombre FROM documentos WHERE id = ?", String.class, id);
        return nombres.stream().findFirst();
    }

    public long findByHash(String hash) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM documentos WHERE hash = ?", Long.class, hash);
        if (id == null) {
            throw new IllegalStateException("No se encontro el documento con hash " + hash);
        }
        return id;
    }

    public List<DocumentSummary> listDocuments() {
        return jdbcTemplate.query("""
                SELECT d.id, d.nombre, d.tipo, d.creado_en, COUNT(f.id) AS total_fragmentos
                FROM documentos d
                LEFT JOIN fragmentos_documento f ON f.documento_id = d.id
                GROUP BY d.id, d.nombre, d.tipo, d.creado_en
                ORDER BY d.id
                """, (rs, rowNum) -> new DocumentSummary(
                rs.getLong("id"),
                rs.getString("nombre"),
                rs.getString("tipo"),
                rs.getInt("total_fragmentos"),
                rs.getTimestamp("creado_en") != null
                        ? rs.getTimestamp("creado_en").toInstant()
                        : null));
    }

    /**
     * Resumen de un documento almacenado, para el listado de la API.
     */
    public record DocumentSummary(long id, String nombre, String tipo, int totalFragmentos,
                                   java.time.Instant creadoEn) {
    }

    /**
     * Inserta los fragmentos con su embedding. Un unico batch garantiza que
     * documento y vectores se escriban juntos.
     */
    public void saveFragments(long documentoId, List<String> fragmentos, float[][] embeddings) {
        if (fragmentos.size() != embeddings.length) {
            throw new IllegalArgumentException(
                    "Cada fragmento necesita su embedding: " + fragmentos.size()
                            + " fragmentos y " + embeddings.length + " embeddings");
        }
        if (fragmentos.isEmpty()) {
            return;
        }

        List<FragmentVector> rows = new java.util.ArrayList<>(fragmentos.size());
        for (int i = 0; i < fragmentos.size(); i++) {
            rows.add(new FragmentVector(i, fragmentos.get(i), embeddings[i]));
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO fragmentos_documento (documento_id, indice, texto, embedding)
                VALUES (?, ?, ?, ?::vector)
                """, rows, rows.size(), (PreparedStatement ps, FragmentVector row) -> {
            ps.setLong(1, documentoId);
            ps.setInt(2, row.indice());
            ps.setString(3, row.texto());
            ps.setString(4, toVectorLiteral(row.embedding()));
        });
    }

    public void deleteDocument(long documentoId) {
        jdbcTemplate.update("DELETE FROM documentos WHERE id = ?", documentoId);
    }

    public void deleteFragments(long documentoId) {
        jdbcTemplate.update("DELETE FROM fragmentos_documento WHERE documento_id = ?", documentoId);
    }

    public int countFragments(long documentoId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fragmentos_documento WHERE documento_id = ?", Integer.class, documentoId);
        return count == null ? 0 : count;
    }

    public int countDocuments() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM documentos", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Busca por similitud coseno usando el operador {@code <=>} de pgvector.
     *
     * <p>La distancia coseno va de 0 a 2, donde 0 es identico. El puntaje
     * expuesto al dominio es {@code 1 - distancia / 2}, normalizado a 0..1, donde
     * mayor es mas relevante.
     */
    public List<FragmentMatch> similaritySearch(String embeddingLiteral, int topK) {
        return jdbcTemplate.query("""
                SELECT f.texto, d.id AS documento_id, d.nombre,
                       f.embedding <=> ?::vector AS distancia
                FROM fragmentos_documento f
                JOIN documentos d ON d.id = f.documento_id
                ORDER BY f.embedding <=> ?::vector
                LIMIT ?
                """, (rs, rowNum) -> new FragmentMatch(
                rs.getString("texto"),
                rs.getLong("documento_id"),
                rs.getString("nombre"),
                (float) rs.getDouble("distancia")),
                embeddingLiteral, embeddingLiteral, Math.max(1, topK));
    }

    /**
     * Busca fragmentos por coincidencia literal de palabras clave.
     *
     * <p>Se usa {@code plainto_tsquery} en vez de {@code to_tsquery} a proposito:
     * {@code plainto_tsquery} trata la entrada como texto y nunca interpreta
     * operadores, de modo que una consulta con sintaxis tsquery malformada no
     * rompe la busqueda.
     *
     * @return fragmentos ordenados por posicion, no por relevancia, porque la
     *         relevance la decide el agente sobre el texto
     */
    public List<KeywordMatch> buscarPorPalabraClave(String query, int limite) {
        return jdbcTemplate.query("""
                SELECT f.texto, f.indice, d.id AS documento_id, d.nombre
                FROM fragmentos_documento f
                JOIN documentos d ON d.id = f.documento_id
                WHERE to_tsvector('spanish', f.texto)
                      @@ plainto_tsquery('spanish', ?)
                ORDER BY d.id, f.indice
                LIMIT ?
                """, (rs, rowNum) -> new KeywordMatch(
                rs.getString("texto"),
                rs.getInt("indice"),
                rs.getLong("documento_id"),
                rs.getString("nombre")),
                query, Math.max(1, limite));
    }

    /**
     * Texto completo de un documento.
     */
    public Optional<String> textoDeDocumento(long documentoId) {
        List<String> textos = jdbcTemplate.queryForList(
                "SELECT contenido_texto FROM documentos WHERE id = ?", String.class, documentoId);
        return textos.stream().findFirst();
    }

    /**
     * Fragmentos de un documento, en orden.
     */
    public List<FragmentoTexto> fragmentosDe(long documentoId, Integer desdeIndice, int limite) {
        return jdbcTemplate.query("""
                SELECT indice, texto FROM fragmentos_documento
                WHERE documento_id = ? AND (? IS NULL OR indice >= ?)
                ORDER BY indice
                LIMIT ?
                """, (rs, rowNum) -> new FragmentoTexto(rs.getInt("indice"), rs.getString("texto")),
                documentoId, desdeIndice, desdeIndice, Math.max(1, limite));
    }

    /**
     * Convierte un vector de floats a la notacion literal de pgvector.
     */
    public static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 10);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Fragmento recuperado por palabras clave.
     */
    public record KeywordMatch(String texto, int indice, long documentoId, String nombre) {
    }

    /**
     * Fragmento con su indice, sin metadatos de documento.
     */
    public record FragmentoTexto(int indice, String texto) {
    }

    /**
     * Fragmento recuperado con su distancia al vector de la consulta.
     */
    public record FragmentMatch(String texto, long documentoId, String nombre, double distancia) {

        /**
         * Convierte la distancia coseno en un puntaje normalizado 0..1.
         */
        public double puntaje() {
            return Math.max(0.0, Math.min(1.0, 1.0 - (distancia / 2.0)));
        }
    }

    /**
     * Par texto + embedding para la insercion.
     */
    public record FragmentVector(int indice, String texto, float[] embedding) {
    }
}