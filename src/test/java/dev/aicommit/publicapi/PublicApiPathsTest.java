package dev.aicommit.publicapi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicApiPathsTest {
    @Test
    void canonicalPathsStayUnchanged() {
        assertEquals("/v1/models", PublicApiPaths.normalize("/v1/models"));
        assertEquals("/v1/chat/completions", PublicApiPaths.normalize("/v1/chat/completions"));
    }

    @Test
    void trailingSlashIsRemoved() {
        assertEquals("/v1/models", PublicApiPaths.normalize("/v1/models/"));
        assertEquals("/v1/chat/completions", PublicApiPaths.normalize("/v1/chat/completions/"));
        assertEquals("/v1/models", PublicApiPaths.normalize("/v1/"));
        assertEquals("/v1/models", PublicApiPaths.normalize("/v1"));
    }

    @Test
    void duplicateSlashesAreCollapsed() {
        assertEquals("/v1/models", PublicApiPaths.normalize("/v1//models"));
        assertEquals("/v1/chat/completions", PublicApiPaths.normalize("//v1///chat/completions"));
    }

    @Test
    void duplicatedV1IsDropped() {
        assertEquals("/v1/chat/completions", PublicApiPaths.normalize("/v1/v1/chat/completions"));
        assertEquals("/v1/models", PublicApiPaths.normalize("/v1/v1/models"));
    }

    @Test
    void duplicatedTrailingRouteIsCollapsed() {
        // A client that appends /chat/completions to a base which already ends
        // with the full endpoint produces this shape.
        assertEquals("/v1/chat/completions",
                PublicApiPaths.normalize("/v1/chat/completions/chat/completions"));
    }

    @Test
    void missingV1IsAdded() {
        assertEquals("/v1/models", PublicApiPaths.normalize("/models"));
        assertEquals("/v1/chat/completions", PublicApiPaths.normalize("/chat/completions"));
    }

    @Test
    void emptyPathFallsBackToModels() {
        assertEquals("/v1/models", PublicApiPaths.normalize(""));
        assertEquals("/v1/models", PublicApiPaths.normalize("/"));
    }

    @Test
    void suffixesAndRouteChecks() {
        assertEquals("/chat/completions", PublicApiPaths.engineSuffix("/v1/chat/completions"));
        assertEquals("/models", PublicApiPaths.engineSuffix("/v1//models"));
        assertTrue(PublicApiPaths.isKnownEngineRoute("/chat/completions"));
        assertTrue(PublicApiPaths.isKnownEngineRoute("/models"));
        assertFalse(PublicApiPaths.isKnownEngineRoute("/unknown"));
    }
}
