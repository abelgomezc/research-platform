package com.abegomez.research.knowledge.api;

import java.util.List;

import com.abegomez.research.common.exception.BusinessException;
import com.abegomez.research.knowledge.KnowledgeProperties;
import com.abegomez.research.knowledge.KnowledgeResult;
import com.abegomez.research.knowledge.KnowledgeSearchPort;
import com.abegomez.research.knowledge.pgvector.DocumentChunkRepository;
import com.abegomez.research.knowledge.pgvector.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API del conocimiento interno: carga, listado y consulta del estado del
 * proveedor. No contiene logica de negocio, solo delega en los servicios.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Conocimiento")
public class KnowledgeController {

    private final DocumentIngestionService ingestionService;
    private final DocumentChunkRepository repository;
    private final KnowledgeSearchPort knowledgeSearchPort;
    private final KnowledgeProperties properties;

    public KnowledgeController(DocumentIngestionService ingestionService,
                               DocumentChunkRepository repository,
                               KnowledgeSearchPort knowledgeSearchPort,
                               KnowledgeProperties properties) {
        this.ingestionService = ingestionService;
        this.repository = repository;
        this.knowledgeSearchPort = knowledgeSearchPort;
        this.properties = properties;
    }

    @PostMapping("/documents")
    @Operation(summary = "Carga un documento al conocimiento interno")
    public ResponseEntity<DocumentResponse> upload(@Valid @RequestBody DocumentUploadRequest request) {
        var result = ingestionService.ingest(request.nombre(), request.tipo(), request.contenido());
        var documentos = repository.listDocuments().stream()
                .filter(documento -> documento.id() == result.documentoId())
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound(
                        "El documento se guardo pero no pudo releerse"));

        DocumentResponse response = toResponse(documentos);
        return ResponseEntity.status(result.yaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    @GetMapping("/documents")
    @Operation(summary = "Lista los documentos del conocimiento interno")
    public ResponseEntity<List<DocumentResponse>> list() {
        return ResponseEntity.ok(repository.listDocuments().stream()
                .map(this::toResponse)
                .toList());
    }

    @DeleteMapping("/documents/{id}")
    @Operation(summary = "Elimina un documento y sus fragmentos")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!ingestionService.delete(id)) {
            throw BusinessException.notFound("No existe el documento con id " + id);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/knowledge/status")
    @Operation(summary = "Devuelve el proveedor de conocimiento activo y su estado")
    public ResponseEntity<KnowledgeStatusResponse> status() {
        String providerId = knowledgeSearchPort.providerId();
        List<String> advertencias = new java.util.ArrayList<>();

        boolean operativo = true;
        String mensaje = "Proveedor operativo";
        Integer topK = null;
        Integer dimension = null;
        String baseUrl = null;
        String descripcion;

        if ("pgvector".equals(providerId)) {
            descripcion = "Busqueda semantica simple con pgvector (distancia coseno)";
            topK = properties.getPgvector().getDefaultTopK();
            dimension = properties.getPgvector().getEmbeddingDimensions();
            int documentos = repository.countDocuments();
            if (documentos == 0) {
                advertencias.add("No hay documentos cargados. El corpus de demostracion se carga al arrancar.");
            }
        }
        else {
            descripcion = "Adaptador HTTP a LocalRAG (solo recuperacion de fragmentos)";
            baseUrl = properties.getLocalrag().getBaseUrl();
            advertencias.add("LocalRAG debe exponer " + properties.getLocalrag().getSearchPath()
                    + " con una lista de {documentId, fileName, pageNumber, chunkNumber, text, score}.");
            advertencias.add("Si el endpoint no existe, las busquedas fallan de forma controlada"
                    + " y las herramientas devuelven un error entendible al agente.");
        }

        return ResponseEntity.ok(new KnowledgeStatusResponse(
                providerId, descripcion, operativo, mensaje, topK, dimension, baseUrl, advertencias));
    }

    @GetMapping("/knowledge/search")
    @Operation(summary = "Busca fragmentos en el conocimiento interno (endpoint de diagnostico)")
    public ResponseEntity<List<KnowledgeResponse>> search(
            @RequestParam String query,
            @RequestParam(required = false) Integer topK) {
        int limit = topK != null ? topK : properties.getPgvector().getDefaultTopK();
        List<KnowledgeResult> results = knowledgeSearchPort.search(query, limit);
        return ResponseEntity.ok(results.stream().map(KnowledgeResponse::from).toList());
    }

    private DocumentResponse toResponse(DocumentChunkRepository.DocumentSummary documento) {
        return new DocumentResponse(documento.id(), documento.nombre(), documento.tipo(),
                documento.totalFragmentos(), documento.creadoEn());
    }

    /**
     * Vista de un fragmento recuperado. Es la misma forma que consumira la tool
     * {@code search_knowledge_base} en la Fase 3.
     */
    public record KnowledgeResponse(
            String texto,
            String documentoId,
            String documentoTitulo,
            String referencia,
            double puntaje) {

        static KnowledgeResponse from(KnowledgeResult result) {
            return new KnowledgeResponse(result.texto(), result.documentoId(),
                    result.documentoTitulo(), result.referencia(), result.puntaje());
        }
    }
}
