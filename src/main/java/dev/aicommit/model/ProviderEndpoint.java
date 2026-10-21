package dev.aicommit.model;

/** Resolved OpenAI-compatible endpoint for the current settings. */
public record ProviderEndpoint(String baseUrl, String apiKey) {
}
