package se.sveki.regulationsagent.web;

import se.sveki.regulationsagent.config.AppProperties;
import se.sveki.regulationsagent.ingestion.Chunk;
import se.sveki.regulationsagent.ingestion.IngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Admin-only ingestion endpoints, protected by a simple {@code X-Admin-Key} header check (see
 * docs/plan.md, "Admin workflow"). Documents are onboarded by first converting them to Markdown
 * outside the app (see docs/sources/), then previewing and committing the resulting {@code .md}
 * file here — the backend itself never parses PDF or HTML.
 */
@RestController
@RequestMapping("/api/admin/documents")
public class AdminIngestionController {

    private final IngestionService ingestionService;
    private final AppProperties props;

    public AdminIngestionController(IngestionService ingestionService, AppProperties props) {
        this.ingestionService = ingestionService;
        this.props = props;
    }

    @PostMapping("/preview")
    public PreviewResponse preview(@RequestHeader("X-Admin-Key") String adminKey,
                                    @RequestParam String documentId) throws IOException {
        checkAdminKey(adminKey);
        Path file = resolveSourceFile(documentId);
        IngestionService.PreviewResult result = ingestionService.preview(file);
        return new PreviewResponse(result.meta().documentId(), result.chunks().size(),
                result.abbreviations(), result.chunks());
    }

    @PostMapping("/commit")
    public ResponseEntity<IngestionService.CommitResult> commit(@RequestHeader("X-Admin-Key") String adminKey,
                                                                  @RequestParam String documentId) throws IOException {
        checkAdminKey(adminKey);
        Path file = resolveSourceFile(documentId);
        try {
            return ResponseEntity.ok(ingestionService.commit(file));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    private void checkAdminKey(String providedKey) {
        String expected = props.admin().apiKey();
        if (expected == null || expected.isBlank() || !expected.equals(providedKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-Admin-Key");
        }
    }

    private Path resolveSourceFile(String documentId) {
        Path file = Path.of(props.sources().directory()).resolve(documentId + ".md");
        if (!Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No such source file: " + file + " (documentId must match a docs/sources/<id>.md filename)");
        }
        return file;
    }

    public record PreviewResponse(String documentId, int chunkCount, java.util.Map<String, String> abbreviations,
                                   List<Chunk> chunks) {
    }
}
