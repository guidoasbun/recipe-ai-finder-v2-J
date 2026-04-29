package io.asbun.backend.model.enums;

public enum BedrockModel {
    CLAUDE_HAIKU("us.anthropic.claude-haiku-4-5-20251001-v1:0"),
    CLAUDE_SONNET("us.anthropic.claude-sonnet-4-6"),
    AMAZON_TITAN("amazon.nova-micro-v1:0"),
    LLAMA3("us.meta.llama3-1-8b-instruct-v1:0"),
    NOVA_LITE("amazon.nova-lite-v1:0");

    private final String modelId;

    BedrockModel(String modelId) {
        this.modelId = modelId;
    }

    public String getModelId() {
        return modelId;
    }
}
