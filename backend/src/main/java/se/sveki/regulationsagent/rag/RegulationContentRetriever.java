package se.sveki.regulationsagent.rag;

import com.datastax.astra.langchain4j.store.embedding.AstraDbEmbeddingStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Retrieval logic for the RAG chat flow: a primary similarity search (optionally scoped to a
 * set of documents via {@code documentFilter}), plus a secondary always-include lookup of
 * glossary chunks for whichever documents the primary search actually touched — so abbreviation
 * definitions are present even when the semantic search on the user's literal question didn't
 * surface them (see docs/plan.md, "RAG query flow").
 */
@Component
public class RegulationContentRetriever {

    private static final int MAX_RESULTS = 6;
    private static final double MIN_SCORE = 0.65;
    private static final int MAX_GLOSSARY_PER_DOCUMENT = 3;

    private final AstraDbEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;

    public RegulationContentRetriever(@Nullable AstraDbEmbeddingStore embeddingStore, EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    public boolean isAvailable() {
        return embeddingStore != null;
    }

    public List<Content> retrieve(String queryText, List<String> documentFilter) {
        List<Content> primary = search(queryText, MAX_RESULTS, MIN_SCORE, documentIdFilter(documentFilter));

        Set<String> documentIds = new LinkedHashSet<>();
        for (Content content : primary) {
            String documentId = content.textSegment().metadata().getString("document_id");
            if (documentId != null) {
                documentIds.add(documentId);
            }
        }
        if (documentIds.isEmpty() && documentFilter != null) {
            documentIds.addAll(documentFilter);
        }
        if (documentIds.isEmpty()) {
            return primary;
        }

        Filter glossaryFilter = MetadataFilterBuilder.metadataKey("chunk_type").isEqualTo("GLOSSARY")
                .and(MetadataFilterBuilder.metadataKey("document_id").isIn(documentIds));
        List<Content> glossary = search(queryText, MAX_GLOSSARY_PER_DOCUMENT * documentIds.size(), 0.0, glossaryFilter);

        Set<String> seen = new LinkedHashSet<>();
        List<Content> merged = new ArrayList<>();
        for (Content content : primary) {
            if (seen.add(contentKey(content))) {
                merged.add(content);
            }
        }
        for (Content content : glossary) {
            if (seen.add(contentKey(content))) {
                merged.add(content);
            }
        }
        return merged;
    }

    private List<Content> search(String queryText, int maxResults, double minScore, @Nullable Filter filter) {
        EmbeddingStoreContentRetriever.EmbeddingStoreContentRetrieverBuilder builder = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore);
        if (filter != null) {
            builder.filter(filter);
        }
        return builder.build().retrieve(Query.from(queryText));
    }

    @Nullable
    private Filter documentIdFilter(@Nullable List<String> documentFilter) {
        if (documentFilter == null || documentFilter.isEmpty()) {
            return null;
        }
        return MetadataFilterBuilder.metadataKey("document_id").isIn(documentFilter);
    }

    private String contentKey(Content content) {
        var metadata = content.textSegment().metadata();
        return metadata.getString("document_id") + "#" + Objects.toString(metadata.getInteger("chunk_index"));
    }
}
