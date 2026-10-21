package dev.aicommit.core;

/** The two Chat Completions messages built from a diff. */
public record CommitPrompt(String system, String user) {
}
