package io.asbun.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Duration;

@Configuration
public class DynamoDbConfig {

    @Value("${aws.region}")
    private String awsRegion;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        // Bounded timeouts + retries are essential for the bulk reindex/backfill jobs: a scan of
        // ~2.2M rows makes thousands of HTTP calls, and without a per-attempt timeout a single
        // stuck/half-open TCP connection blocks the paginator forever (observed: a scan thread
        // parked in sun.nio.ch.Net.poll for ~50 min with no progress). apiCallAttemptTimeout
        // makes a wedged attempt fail fast so the retry policy can reissue it on a fresh
        // connection; apiCallTimeout bounds the whole operation.
        return DynamoDbClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(AwsCrtHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(10))
                        // Ample pool so 8 parallel scan segments each get a connection.
                        .maxConcurrency(64))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        // Per-HTTP-attempt ceiling: a stuck read is abandoned and retried.
                        .apiCallAttemptTimeout(Duration.ofSeconds(30))
                        // Whole-operation ceiling across retries.
                        .apiCallTimeout(Duration.ofSeconds(120))
                        // Retry throttling/5xx/timeouts with backoff (numRetries beyond the first try).
                        .retryPolicy(RetryPolicy.builder().numRetries(8).build())
                        .build())
                .build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }
}
