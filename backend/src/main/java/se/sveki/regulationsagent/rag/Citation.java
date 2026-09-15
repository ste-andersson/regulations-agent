package se.sveki.regulationsagent.rag;

/**
 * A single source citation, built server-side from the metadata of a chunk that was actually
 * retrieved for the answer (never parsed out of the model's free-text response) — see
 * docs/plan.md, "RAG query flow". {@code sectionNumber} and {@code pageNumber} are nullable since
 * not every source document has formal section numbering or page numbers.
 */
public record Citation(
        String documentId,
        String documentTitle,
        String federation,
        String sectionNumber,
        String sectionTitle,
        Integer pageNumber,
        String sourceUrl,
        String snippet
) {
}
