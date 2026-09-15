package se.sveki.regulationsagent.config;

import com.datastax.astra.client.DataAPIClient;
import com.datastax.astra.client.collections.Collection;
import com.datastax.astra.client.collections.definition.CollectionDefinition;
import com.datastax.astra.client.collections.definition.documents.Document;
import com.datastax.astra.client.core.vector.SimilarityMetric;
import com.datastax.astra.client.databases.Database;
import com.datastax.astra.langchain4j.store.embedding.AstraDbEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Astra DB vector store. See docs/plan.md, "Astra DB: setup och schema" — the
 * collection ({@code regulation_chunks} by default) must already exist with the right vector
 * dimension (1536 for text-embedding-3-small); this config will create it on first startup if
 * missing, but the *database itself* (and its region) must be created manually in the Astra
 * console first (see the plan for the EU-region availability caveat encountered during setup).
 * <p>
 * Returns {@code null} when Astra credentials are not yet configured, so the rest of the app
 * (in particular the Markdown-chunking preview endpoint) can still be exercised locally without
 * an Astra DB account. Only the ingestion "commit" step and RAG retrieval actually need this bean.
 */
@Configuration
public class AstraDbConfig {

    private static final Logger log = LoggerFactory.getLogger(AstraDbConfig.class);

    @Bean
    public AstraDbEmbeddingStore astraDbEmbeddingStore(AppProperties props) {
        if (!props.astra().isConfigured()) {
            log.warn("Astra DB is not configured (ASTRA_DB_APPLICATION_TOKEN / ASTRA_DB_API_ENDPOINT missing) "
                    + "- vector store bean not created. Ingestion 'commit' and RAG retrieval will be unavailable "
                    + "until these are set.");
            return null;
        }
        DataAPIClient client = new DataAPIClient(props.astra().applicationToken());
        Database database = client.getDatabase(props.astra().apiEndpoint(), props.astra().keyspace());

        String collectionName = props.astra().collection();
        Collection<Document> collection;
        if (database.collectionExists(collectionName)) {
            collection = database.getCollection(collectionName);
        } else {
            log.info("Astra DB collection '{}' does not exist yet - creating it (dimension={}, metric=COSINE).",
                    collectionName, props.openai().embeddingDimension());
            collection = database.createCollection(collectionName,
                    new CollectionDefinition().vector(props.openai().embeddingDimension(), SimilarityMetric.COSINE));
        }
        return new AstraDbEmbeddingStore(collection);
    }
}
