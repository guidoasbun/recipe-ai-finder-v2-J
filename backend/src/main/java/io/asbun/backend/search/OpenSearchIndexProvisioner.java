package io.asbun.backend.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.asbun.backend.config.OpenSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Creates the catalog OpenSearch index with the mapping defined in design.md §3, if it does
 * not already exist. Idempotent: safe to call repeatedly (the reindex job, task 6, invokes it
 * before bulk indexing).
 *
 * <p>The mapping uses a Faiss HNSW {@code knn_vector} of dimension 1024 with cosine space,
 * which aligns with what an Amazon OpenSearch Serverless NextGen vector search collection
 * supports. Vector quantization is controlled by {@code opensearch.knn.quantization}
 * ({@code none | fp16 | byte}); it stays {@code none} until the full 2.2M load.
 *
 * <p>Only active when {@code catalog.search.backend=opensearch}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catalog.search.backend", havingValue = "opensearch")
public class OpenSearchIndexProvisioner {

    static final int VECTOR_DIMENSION = 1024;

    private final OpenSearchClient client;
    private final OpenSearchProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates the configured index with the catalog mapping if it is absent. Returns
     * {@code true} if the index was created, {@code false} if it already existed.
     */
    public boolean ensureIndex() {
        String index = properties.getIndex();
        try {
            boolean exists = client.indices().exists(e -> e.index(index)).value();
            if (exists) {
                log.info("OpenSearch index '{}' already exists; skipping creation.", index);
                return false;
            }

            CreateIndexRequest request = new CreateIndexRequest.Builder()
                    .index(index)
                    .settings(buildSettings())
                    .mappings(buildMapping())
                    .build();

            client.indices().create(request);
            log.info("Created OpenSearch index '{}' (dim={}, quantization={}).",
                    index, VECTOR_DIMENSION, properties.getKnn().getQuantization());
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to ensure OpenSearch index '" + index + "'", e);
        }
    }

    /**
     * Serverless (aoss) manages k-NN implicitly and rejects the explicit {@code index.knn}
     * setting, so it is only set for the managed-domain ("es") signing service.
     */
    private IndexSettings buildSettings() {
        boolean managedDomain = "es".equalsIgnoreCase(properties.getSigningService());
        IndexSettings.Builder builder = new IndexSettings.Builder();
        if (managedDomain) {
            builder.knn(true);
        }
        return builder.build();
    }

    private TypeMapping buildMapping() throws IOException {
        String json = buildMappingJson();
        return TypeMapping._DESERIALIZER.deserialize(
                client._transport().jsonpMapper().jsonProvider()
                        .createParser(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))),
                client._transport().jsonpMapper());
    }

    /**
     * Builds the mapping JSON from design.md §3. Kept as JSON (rather than the verbose typed
     * k-NN builders) so the Faiss method/encoder definition is explicit and version-stable.
     */
    String buildMappingJson() {
        ObjectNode props = objectMapper.createObjectNode();

        props.set("catalogRecipeId", keyword());

        ObjectNode title = objectMapper.createObjectNode();
        title.put("type", "text");
        ObjectNode titleFields = objectMapper.createObjectNode();
        titleFields.set("kw", keyword());
        title.set("fields", titleFields);
        props.set("title", title);

        props.set("description", text(true));
        props.set("ingredients", text(true));
        props.set("steps", text(false));

        props.set("dietaryTags", keyword());
        props.set("imageUrl", keywordNotIndexed());
        props.set("sourceName", keyword());
        props.set("sourceUrl", keywordNotIndexed());
        props.set("sourceLicense", keywordNotIndexed());
        props.set("sourceCountry", keyword());

        // Reserved for the future private-recipe feature (design §9a): "public" or a userId.
        // Unused today; present so the index needs no re-mapping when that feature ships.
        props.set("ownerScope", keyword());

        props.set("embedding", embeddingField());

        ObjectNode mappings = objectMapper.createObjectNode();
        mappings.set("properties", props);
        return mappings.toString();
    }

    private ObjectNode embeddingField() {
        ObjectNode method = objectMapper.createObjectNode();
        method.put("name", "hnsw");
        method.put("space_type", "cosinesimil");
        method.put("engine", "faiss");

        ObjectNode methodParams = objectMapper.createObjectNode();
        methodParams.put("ef_construction", 128);
        methodParams.put("m", 16);

        // Quantization knob (design §3): fp16 = Faiss scalar (fp16) encoder; byte handled via
        // data_type; none = full-precision float (default).
        String quantization = properties.getKnn().getQuantization();
        ObjectNode field = objectMapper.createObjectNode();
        field.put("type", "knn_vector");
        field.put("dimension", VECTOR_DIMENSION);

        if ("fp16".equalsIgnoreCase(quantization)) {
            ObjectNode encoder = objectMapper.createObjectNode();
            encoder.put("name", "sq");
            ObjectNode encoderParams = objectMapper.createObjectNode();
            encoderParams.put("type", "fp16");
            encoder.set("parameters", encoderParams);
            methodParams.set("encoder", encoder);
        } else if ("byte".equalsIgnoreCase(quantization)) {
            field.put("data_type", "byte");
        }

        method.set("parameters", methodParams);
        field.set("method", method);
        return field;
    }

    private ObjectNode keyword() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "keyword");
        return node;
    }

    private ObjectNode keywordNotIndexed() {
        ObjectNode node = keyword();
        node.put("index", false);
        return node;
    }

    private ObjectNode text(boolean indexed) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "text");
        if (!indexed) {
            node.put("index", false);
        }
        return node;
    }
}
