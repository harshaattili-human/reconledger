package dev.harshaattili.reconledger;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;

/** A write request must assign each JSON object field exactly once. */
@Configuration(proxyBeanMethods = false)
public class JsonInputConfiguration {
    @Bean
    JsonMapperBuilderCustomizer rejectDuplicateObjectFields() {
        // Streaming detection also covers nested objects and decoded escaped names.
        // Keep Boot's mapper/modules and the existing unknown-field policy.
        return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }
}
