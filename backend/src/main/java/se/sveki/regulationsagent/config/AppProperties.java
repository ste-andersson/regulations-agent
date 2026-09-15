package se.sveki.regulationsagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        OpenAi openai,
        Astra astra,
        Langfuse langfuse,
        Admin admin,
        Sources sources
) {
    public record OpenAi(String apiKey, String chatModel, String embeddingModel, int embeddingDimension) {
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Astra(String applicationToken, String apiEndpoint, String keyspace, String collection) {
        public boolean isConfigured() {
            return applicationToken != null && !applicationToken.isBlank()
                    && apiEndpoint != null && !apiEndpoint.isBlank();
        }
    }

    public record Langfuse(String publicKey, String secretKey, String host) {
        public boolean isConfigured() {
            return publicKey != null && !publicKey.isBlank() && secretKey != null && !secretKey.isBlank();
        }
    }

    public record Admin(String apiKey) {
    }

    public record Sources(String directory) {
    }
}
