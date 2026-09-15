package se.sveki.regulationsagent.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import java.time.Instant;
import java.util.List;

/**
 * The document registry: source of truth for "which documents have been ingested", decoupled
 * from Astra DB (which only stores chunks). Used to populate a document filter in the frontend
 * and to build metadata filters at retrieval time.
 */
@Entity
public class RegulationDocument {

    @Id
    private String documentId;

    private String title;
    private String federation;

    @ElementCollection
    private List<String> danceTypes;

    private String version;
    private String effectiveDate;
    private String sourceFilename;
    private Instant ingestedAt;
    private String status;

    @Lob
    @Column(length = 10000)
    private String abbreviationsJson;

    protected RegulationDocument() {
        // JPA
    }

    public RegulationDocument(String documentId, String title, String federation, List<String> danceTypes,
                               String version, String effectiveDate, String sourceFilename) {
        this.documentId = documentId;
        this.title = title;
        this.federation = federation;
        this.danceTypes = danceTypes;
        this.version = version;
        this.effectiveDate = effectiveDate;
        this.sourceFilename = sourceFilename;
        this.status = "PENDING";
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getTitle() {
        return title;
    }

    public String getFederation() {
        return federation;
    }

    public List<String> getDanceTypes() {
        return danceTypes;
    }

    public String getVersion() {
        return version;
    }

    public String getEffectiveDate() {
        return effectiveDate;
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getAbbreviationsJson() {
        return abbreviationsJson;
    }

    public void markIngested(int chunkCount, String abbreviationsJson) {
        this.status = "INGESTED (" + chunkCount + " chunks)";
        this.ingestedAt = Instant.now();
        this.abbreviationsJson = abbreviationsJson;
    }
}
