package io.asbun.backend.model.enums;

public enum BedrockModel {
    CLAUDE_HAIKU("anthropic.claude-3-haiku-20240307-v1:0"),
    CLAUDE_SONNET("anthropic.claude-3-5-sonnet-20241022-v2:0"),
    AMAZON_TITAN("amazon.titan-text-express-v1"),
    LLAMA3("meta.llama3-8b-instruct-v1:0"),
    NOVA_LITE("amazon.nova-lite-v1:0");

    private final String modelId;

    BedrockModel(String modelId) {
        this.modelId = modelId;
    }

    public String getModelId() {
        return modelId;
    }
}
