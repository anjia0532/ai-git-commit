package dev.aicommit.bridge;

/** A running embedded engine endpoint. */
public record BridgeEndpoint(String baseUrl, String apiKey, int port) {
}
