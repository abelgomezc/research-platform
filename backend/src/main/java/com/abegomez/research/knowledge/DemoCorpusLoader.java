package com.abegomez.research.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.abegomez.research.knowledge.pgvector.DocumentIngestionService;

/**
 * Carga el corpus de demostracion al arrancar.
 *
 * <p>Es idempotente: la ingesta se omite si el contenido ya esta (mismo hash), de
 * modo que reiniciar el contenedor no duplica documentos ni recalcula embeddings.
 *
 * <p>Solo corre con el proveedor pgvector. Con el adaptador de LocalRAG el
 * corpus se gestiona en el servicio externo, no aqui.
 */
@Component
@ConditionalOnProperty(name = "app.demo-corpus.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "app.knowledge.provider", havingValue = "pgvector", matchIfMissing = true)
public class DemoCorpusLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoCorpusLoader.class);

    private static final String CORPUS_PATTERN = "classpath:knowledge/corpus/*.md";

    private final DocumentIngestionService ingestionService;
    private final Path externalCorpusPath;

    @Autowired
    public DemoCorpusLoader(DocumentIngestionService ingestionService) {
        this(ingestionService, null);
    }

    public DemoCorpusLoader(DocumentIngestionService ingestionService, Path externalCorpusPath) {
        this.ingestionService = ingestionService;
        this.externalCorpusPath = externalCorpusPath;
    }

    @Override
    public void run(ApplicationArguments args) {
        int cargados = 0;
        int omitidos = 0;

        for (Resource resource : resolveResources()) {
            try (InputStream in = resource.getInputStream()) {
                String contenido = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String nombre = resourceName(resource);
                var result = ingestionService.ingest(nombre, "MD", contenido);
                if (result.yaExistia()) {
                    omitidos++;
                }
                else {
                    cargados++;
                }
            }
            catch (IOException ex) {
                log.warn("No se pudo leer un documento del corpus: {}", ex.getMessage());
            }
        }

        if (externalCorpusPath != null && Files.isDirectory(externalCorpusPath)) {
            cargados += cargarDelSistemaDeArchivos(externalCorpusPath);
        }

        log.info("Corpus de demostracion cargado: {} documentos nuevos, {} ya existentes",
                cargados, omitidos);
    }

    private Resource[] resolveResources() {
        try {
            return resolver().getResources(CORPUS_PATTERN);
        }
        catch (IOException ex) {
            log.warn("No se pudo resolver el classpath del corpus: {}", ex.getMessage());
            return new Resource[0];
        }
    }

    /**
     * Nombre estable del documento. El classloader puede dar un nombre distinto
     * si el recurso viene de un jar, por eso se toma la ultima parte de la ruta.
     */
    private String resourceName(Resource resource) throws IOException {
        String filename = resource.getFilename();
        if (filename != null && !filename.isBlank()) {
            return filename;
        }
        String description = resource.getURL().toString();
        return description.substring(description.lastIndexOf('/') + 1);
    }

    private int cargarDelSistemaDeArchivos(Path directorio) {
        int cargados = 0;
        try (var archivos = Files.list(directorio)) {
            List<Path> documentos = archivos
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path documento : documentos) {
                String contenido = Files.readString(documento, StandardCharsets.UTF_8);
                var result = ingestionService.ingest(documento.getFileName().toString(), "MD", contenido);
                if (!result.yaExistia()) {
                    cargados++;
                }
            }
        }
        catch (IOException ex) {
            throw new UncheckedIOException("No se pudo leer el corpus externo en " + directorio, ex);
        }
        return cargados;
    }

    private PathMatchingResourcePatternResolver resolver() {
        return new PathMatchingResourcePatternResolver();
    }
}
