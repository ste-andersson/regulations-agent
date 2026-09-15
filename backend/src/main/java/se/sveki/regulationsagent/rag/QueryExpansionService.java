package se.sveki.regulationsagent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import se.sveki.regulationsagent.ingestion.RegulationDocument;
import se.sveki.regulationsagent.ingestion.RegulationDocumentRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands abbreviations (RG, AR1-3, UK, FS, ITT, ...) found in a user's question with their
 * expansion, so semantic search still works when the question only uses the abbreviation and
 * the matching prose in the source document spells the term out (see docs/plan.md, "RAG query
 * flow"). The expansion is appended to the text used for embedding search only — it is not
 * shown to the user and does not change the question passed to the chat model.
 */
@Component
public class QueryExpansionService {

    private static final Pattern ABBREVIATION_TOKEN = Pattern.compile("\\b[A-ZÅÄÖ]{2,6}[0-9]?\\b");

    private final RegulationDocumentRepository documentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryExpansionService(RegulationDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public String expandForSearch(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = ABBREVIATION_TOKEN.matcher(query);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        if (tokens.isEmpty()) {
            return query;
        }

        Map<String, String> abbreviations = loadAllAbbreviations();
        StringBuilder expanded = new StringBuilder(query);
        for (String token : tokens) {
            String expansion = abbreviations.get(token);
            if (expansion != null) {
                expanded.append(" (").append(token).append(" = ").append(expansion).append(")");
            }
        }
        return expanded.toString();
    }

    private Map<String, String> loadAllAbbreviations() {
        Map<String, String> merged = new HashMap<>();
        for (RegulationDocument document : documentRepository.findAll()) {
            String json = document.getAbbreviationsJson();
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                merged.putAll(objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
                }));
            } catch (Exception ignored) {
                // A malformed abbreviations blob for one document should not break query expansion
                // for everything else.
            }
        }
        return merged;
    }
}
