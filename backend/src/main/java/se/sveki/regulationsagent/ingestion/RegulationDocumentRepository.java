package se.sveki.regulationsagent.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RegulationDocumentRepository extends JpaRepository<RegulationDocument, String> {
}
