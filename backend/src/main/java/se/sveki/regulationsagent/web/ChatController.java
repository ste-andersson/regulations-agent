package se.sveki.regulationsagent.web;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 placeholder: a plain (non-RAG) chat endpoint used to verify the OpenAI wiring end to
 * end before retrieval is layered in (see docs/plan.md, "Fas 1"). This will be replaced by a
 * retrieval-augmented {@code RegulationAssistant} (AiServices) in Fas 2, with citations built
 * from the actually-retrieved chunks.
 */
@RestController
public class ChatController {

    private final ChatModel chatModel;

    public ChatController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @PostMapping("/api/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String answer = chatModel.chat(request.message());
        return new ChatResponse(answer);
    }

    public record ChatRequest(String message) {
    }

    public record ChatResponse(String answer) {
    }
}
