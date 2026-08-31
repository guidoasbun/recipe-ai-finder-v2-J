package io.asbun.backend.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.asbun.backend.config.OpenSearchProperties;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the OpenSearch catalog index mapping (design.md §3). Verifies the field
 * types, the Faiss HNSW knn_vector definition, the reserved ownerScope field, and that the
 * quantization knob (none | fp16 | byte) produces the correct mapping fragment.
 *
 * Feature: opensearch-catalog-backend
 * Validates: Task 2.1, 2.3, 2.4 (Requirements 3.1, 3.2, 3.3, 3.4, 10.1)
 */
class OpenSearchIndexProvisionerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OpenSearchIndexProvisioner provisioner(String quantization, String signingService) {
        OpenSearchProperties props = new OpenSearchProperties();
        props.setIndex("catalog-recipes");
        props.setSigningService(signingService);
        props.getKnn().setQuantization(quantization);
        return new OpenSearchIndexProvisioner(mock(OpenSearchClient.class), props);
    }

    private JsonNode mappingProps(OpenSearchIndexProvisioner provisioner) throws Exception {
        JsonNode root = mapper.readTree(provisioner.buildMappingJson());
        return root.path("properties");
    }

    @Test
    void mapping_hasExpectedFieldTypes() throws Exception {
        JsonNode props = mappingProps(provisioner("none", "aoss"));

        assertThat(props.path("catalogRecipeId").path("type").asText()).isEqualTo("keyword");
        assertThat(props.path("dietaryTags").path("type").asText()).isEqualTo("keyword");
        assertThat(props.path("title").path("type").asText()).isEqualTo("text");
        assertThat(props.path("title").path("fields").path("kw").path("type").asText())
                .isEqualTo("keyword");
        assertThat(props.path("description").path("type").asText()).isEqualTo("text");
        assertThat(props.path("ingredients").path("type").asText()).isEqualTo("text");
    }

    @Test
    void steps_and_attributionUrls_areNotIndexed() throws Exception {
        JsonNode props = mappingProps(provisioner("none", "aoss"));

        assertThat(props.path("steps").path("index").asBoolean(true)).isFalse();
        assertThat(props.path("imageUrl").path("index").asBoolean(true)).isFalse();
        assertThat(props.path("sourceUrl").path("index").asBoolean(true)).isFalse();
        assertThat(props.path("sourceLicense").path("index").asBoolean(true)).isFalse();
    }

    @Test
    void reservesOwnerScopeKeywordField_forFuturePrivateRecipes() throws Exception {
        JsonNode props = mappingProps(provisioner("none", "aoss"));
        assertThat(props.path("ownerScope").path("type").asText()).isEqualTo("keyword");
    }

    @Test
    void embedding_isFaissHnswCosine_1024() throws Exception {
        JsonNode embedding = mappingProps(provisioner("none", "aoss")).path("embedding");

        assertThat(embedding.path("type").asText()).isEqualTo("knn_vector");
        assertThat(embedding.path("dimension").asInt()).isEqualTo(1024);

        JsonNode method = embedding.path("method");
        assertThat(method.path("name").asText()).isEqualTo("hnsw");
        assertThat(method.path("engine").asText()).isEqualTo("faiss");
        assertThat(method.path("space_type").asText()).isEqualTo("cosinesimil");
    }

    @Test
    void quantizationNone_hasNoEncoderAndFloatVector() throws Exception {
        JsonNode embedding = mappingProps(provisioner("none", "aoss")).path("embedding");

        assertThat(embedding.path("method").path("parameters").has("encoder")).isFalse();
        assertThat(embedding.has("data_type")).isFalse();
    }

    @Test
    void quantizationFp16_addsScalarEncoder() throws Exception {
        JsonNode embedding = mappingProps(provisioner("fp16", "aoss")).path("embedding");

        JsonNode encoder = embedding.path("method").path("parameters").path("encoder");
        assertThat(encoder.path("name").asText()).isEqualTo("sq");
        assertThat(encoder.path("parameters").path("type").asText()).isEqualTo("fp16");
    }

    @Test
    void quantizationByte_setsByteDataType() throws Exception {
        JsonNode embedding = mappingProps(provisioner("byte", "aoss")).path("embedding");
        assertThat(embedding.path("data_type").asText()).isEqualTo("byte");
    }
}
