package dev.aicommit.model;

import dev.aicommit.bridge.BridgeEndpoint;
import dev.aicommit.bridge.BridgeException;
import dev.aicommit.bridge.OpenCodeZenBridge;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.AiCommitException;
import dev.aicommit.publicapi.PublicApiServer;

/** Turns the settings snapshot into the endpoint the client should call. */
public final class ProviderResolver {
    private ProviderResolver() {
    }

    public static ProviderEndpoint resolve(AiCommitConfig config) throws AiCommitException {
        if (config.isBuiltInEngine()) {
            try {
                BridgeEndpoint endpoint = OpenCodeZenBridge.getInstance()
                        .ensureRunning(config.upstreamProxy());
                ProviderEndpoint resolved = new ProviderEndpoint(endpoint.baseUrl(), endpoint.apiKey());
                PublicApiServer.getInstance().ensureStarted(config, resolved);
                return resolved;
            } catch (BridgeException e) {
                throw new AiCommitException(e.getMessage(), false, e);
            }
        }
        if (config.baseUrl().isBlank()) {
            throw new AiCommitException("Configure an endpoint URL in Settings | Tools | AI Git Commit",
                    false, null);
        }
        return new ProviderEndpoint(config.baseUrl(), config.apiKey());
    }
}
