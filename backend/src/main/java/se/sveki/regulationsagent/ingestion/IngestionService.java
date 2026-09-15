package se.sveki.regulationsagent.ingestion;

import com.datastax.astra.client.core.query.Filters;
import com.datastax.astra.langchain4j.store.embedding.AstraDbEmbeddingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates ingestion of a single already-converted {@code docs/sources/<id>.md} file:
 * chunking (always available), and embedding + upsert into Astra DB (only once Astra/OpenAI
 * are configured). See docs/plan.md, "Admin workflow" for why this is split into preview/commit.
 */
@Service
public class IngestionService {

    private final MarkdownChunkAssembler chunkAssembler;
    private final AbbreviationExtractor abbreviationExtractor;
    private final EmbeddingModel embeddingModel;
    private final AstraDbEmbeddingStore embeddingStore;
    private final RegulationDocumentRepository documentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IngestionService(MarkdownChunkAssembler chunkAssembler,
                             AbbreviationExtractor abbreviationExtractor,
                             EmbeddingModel embeddingModel,
                             @Nullable AstraDbEmbeddingStore embeddingStore,
                             RegulationDocumentRepository documentRepository) {
        this.chunkAssembler = chunkAssembler;
        this.abbreviationExtractor = abbreviationExtractor;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentRepository = documentRepository;
    }

    /**
     * Parses and chunks a Markdown file without embedding or storing anything, so the result
     * can be reviewed by a human before it is committed (see docs/plan.md's data-fidelity
     * argument for why this preview step exists at all).
     */
    public PreviewResult preview(Path markdownFile) throws IOException {
        String raw = Files.readString(markdownFile, StandardCharsets.UTF_8);
        FrontMatterParser.Result parsed = FrontMatterParser.parse(raw);
        DocumentFrontMatter meta = parsed.toDocumentFrontMatter();
        List<Chunk> chunks = chunkAssembler.assemble(parsed.body(), meta);
        Map<String, String> abbreviations = abbreviationExtractor.extract(chunks);
        return new PreviewResult(meta, chunks, abbreviations);
    }

    /**
     * Runs {@link #preview(Path)} and then embeds + upserts every chunk into Astra DB, and
     * upserts the document's registry row. Requires both OpenAI and Astra DB to be configured.
     */
    public CommitResult commit(Path markdownFile) throws IOException {
        if (embeddingStore == null) {
            throw new IllegalStateException(
                    "Astra DB is not configured (ASTRA_DB_APPLICATION_TOKEN / ASTRA_DB_API_ENDPOINT missing) - "
                            + "cannot commit. Preview still works without it.");
        }
        PreviewResult preview = preview(markdownFile);

        // addAll() always generates fresh Astra document IDs (it has no concept of "this chunk
        // already exists, replace it") - re-committing the same document without first deleting
        // its previous chunks would silently accumulate duplicates on every re-ingestion. Deleting
        // by document_id first makes commit() idempotent, matching how re-running ingestion after
        // fixing a bug (e.g. a chunk-classification heuristic) is expected to behave.
        embeddingStore.astraDBCollection().deleteMany(Filters.eq("document_id", preview.meta().documentId()));

        List<TextSegment> segments = new ArrayList<>(preview.chunks().size());
        for (Chunk chunk : preview.chunks()) {
            segments.add(TextSegment.from(chunk.text(), toMetadata(chunk)));
        }
        Response<List<Embedding>> embeddings = embeddingModel.embedAll(segments);
        embeddingStore.addAll(embeddings.content(), segments);

        DocumentFrontMatter meta = preview.meta();
        RegulationDocument document = documentRepository.findById(meta.documentId())
                .orElseGet(() -> new RegulationDocument(
                        meta.documentId(), meta.title(), meta.federation(), meta.danceTypes(),
                        meta.version(), meta.effectiveDate(), meta.sourceFile()));
        String abbreviationsJson = toJson(preview.abbreviations());
        document.markIngested(preview.chunks().size(), abbreviationsJson);
        documentRepository.save(document);

        return new CommitResult(meta.documentId(), preview.chunks().size());
    }

    private Metadata toMetadata(Chunk chunk) {
        Metadata metadata = new Metadata();
        putIfNotNull(metadata, "document_id", chunk.documentId());
        putIfNotNull(metadata, "document_title", chunk.documentTitle());
        putIfNotNull(metadata, "federation", chunk.federation());
        putIfNotNull(metadata, "version", chunk.version());
        putIfNotNull(metadata, "effective_date", chunk.effectiveDate());
        putIfNotNull(metadata, "source_type", chunk.sourceType());
        putIfNotNull(metadata, "source_language", chunk.sourceLanguage());
        putIfNotNull(metadata, "section_number", chunk.sectionNumber());
        putIfNotNull(metadata, "section_title", chunk.sectionTitle());
        putIfNotNull(metadata, "section_path", chunk.sectionPath());
        putIfNotNull(metadata, "source_url", chunk.sourceUrl());
        putIfNotNull(metadata, "chunk_type", chunk.chunkType().name());
        if (chunk.pageNumber() != null) {
            metadata.put("page_number", chunk.pageNumber());
        }
        metadata.put("chunk_index", chunk.chunkIndex());
        return metadata;
    }

    private void putIfNotNull(Metadata metadata, String key, String value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String toJson(Map<String, String> abbreviations) {
        try {
            return objectMapper.writeValueAsString(abbreviations);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize abbreviations map", e);
        }
    }

    public record PreviewResult(DocumentFrontMatter meta, List<Chunk> chunks, Map<String, String> abbreviations) {
    }

    public record CommitResult(String documentId, int chunkCount) {
    }
}
