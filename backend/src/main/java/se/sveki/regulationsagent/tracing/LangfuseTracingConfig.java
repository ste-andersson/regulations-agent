package se.sveki.regulationsagent.tracing;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import se.sveki.regulationsagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Wires tracing to Langfuse via plain OpenTelemetry (see docs/plan.md, "Langfuse integration").
 * LangChain4j has no first-party Langfuse SDK, and Langfuse's legacy REST ingestion API
 * (POST /api/public/ingestion) is being sunset on Langfuse Cloud in favor of its OTLP endpoint,
 * so this uses the OTel Java SDK's OTLP/HTTP exporter directly rather than hand-rolling the
 * legacy REST batch format.
 * <p>
 * Returns {@code null} when Langfuse is not configured, so the rest of the app works without it -
 * {@link se.sveki.regulationsagent.rag.RagChatService} treats a {@code null} {@link Tracer} as
 * "tracing disabled" rather than failing.
 */
@Configuration
public class LangfuseTracingConfig {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTracingConfig.class);
    private static final String INSTRUMENTATION_NAME = "se.sveki.regulationsagent";

    @Bean
    public Tracer langfuseTracer(AppProperties props) {
        if (!props.langfuse().isConfigured()) {
            log.warn("Langfuse is not configured (LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY missing) - "
                    + "chat tracing is disabled.");
            return null;
        }
        String authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (props.langfuse().publicKey() + ":" + props.langfuse().secretKey())
                        .getBytes(StandardCharsets.UTF_8));

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(props.langfuse().host() + "/api/public/otel/v1/traces")
                .addHeader("Authorization", authHeader)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().toBuilder()
                        .put("service.name", "regulations-agent-backend")
                        .build())
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();

        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        return openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }
}
