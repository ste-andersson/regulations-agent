package se.sveki.regulationsagent.web;

import se.sveki.regulationsagent.rag.Citation;
import se.sveki.regulationsagent.rag.RagChatService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The RAG chat endpoint (see docs/plan.md, "RAG query flow"). Citations are built server-side
 * by {@link RagChatService} from the chunks actually retrieved, not parsed out of the model's
 * free-text answer.
 */
@RestController
public class ChatController {

    private final RagChatService ragChatService;

    public ChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @PostMapping("/api/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        try {
            RagChatService.RagChatResult result = ragChatService.chat(
                    request.conversationId(), request.message(), request.documentFilter());
            return new ChatResponse(result.conversationId(), result.answer(), result.citations());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    public record ChatRequest(String conversationId, String message, List<String> documentFilter) {
    }

    public record ChatResponse(String conversationId, String answer, List<Citation> citations) {
    }
}
