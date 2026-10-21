package dev.aicommit.core;

/**
 * Builds the commit-message prompt.
 *
 * <p>The diff is trimmed to the configured input budget before it is sent. The
 * budget is a token estimate, not an exact tokenizer count, so a little space
 * is kept for the system prompt and the model's own output.</p>
 */
public final class CommitPromptBuilder {
    private static final int PROMPT_OVERHEAD_TOKENS = 256;

    /**
     * Editable default prompt. The placeholders are replaced by the builder;
     * users can change the wording, add project rules, or switch the whole
     * template to Chinese in Settings.
     */
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are a senior software engineer who writes precise Git commit messages.
            Analyse the change below and reply with ONLY the commit message.

            Format rules:
            {format_rules}
            - The subject must be imperative, at most {subject_limit} characters, and must not end with a period.
            {body_rules}
            - Write the message in {language}.
            - Never use Markdown fences, headings, quotes, labels or explanations.
            """;

    public CommitPrompt build(AiCommitConfig config, String repository, DiffDigest digest, int modelMaxInput) {
        String language = config.resolvedLanguage();
        String system = buildSystem(config, language);
        String user = buildUser(config, repository, digest, system, modelMaxInput);
        return new CommitPrompt(system, user);
    }

    private String buildSystem(AiCommitConfig config, String language) {
        String formatRules = "plain".equals(config.style())
                ? "- Start with a single short imperative subject line, then optionally a body.\n"
                : """
                - Use Conventional Commits: <type>(<scope>): <subject>.
                - type is one of feat, fix, refactor, perf, docs, test, build, ci, chore, style, revert.
                - scope is optional and names the affected module or area.
                """;
        String bodyRules = config.includeBody()
                ? "- For non-trivial changes add a blank line and a body of 1-4 short bullet points, "
                + "wrapped at " + config.bodyWidth()
                + " characters, explaining what changed and why.\n"
                + "- Do not describe the review process, the tools used, or files that did not change.\n"
                : "- Write only the subject line, no body.\n";
        String languageName = "zh".equals(language) ? "Simplified Chinese" : "English";

        String template = config.promptTemplate() == null || config.promptTemplate().isBlank()
                ? DEFAULT_SYSTEM_PROMPT
                : config.promptTemplate();
        String system = template
                .replace("{format_rules}", formatRules)
                .replace("{body_rules}", bodyRules)
                .replace("{subject_limit}", Integer.toString(config.subjectLimit()))
                .replace("{body_limit}", Integer.toString(config.bodyWidth()))
                .replace("{language}", languageName)
                .trim();
        if (!template.contains("{language}")) {
            system = system + "\n- Write the message in " + languageName + ".";
        }
        if (!config.customInstructions().isBlank()) {
            system = system + "\n\nAdditional project rules:\n" + config.customInstructions();
        }
        return system + "\n";
    }

    private String buildUser(AiCommitConfig config, String repository, DiffDigest digest,
                             String system, int modelMaxInput) {
        int inputBudget = config.maxInputTokens();
        if (modelMaxInput > 0) {
            inputBudget = Math.min(inputBudget, Math.max(1_024, modelMaxInput - config.maxOutputTokens()));
        }
        int systemTokens = TokenEstimator.estimate(system);
        int fileListTokens = TokenEstimator.estimate(fileList(digest));
        int available = Math.max(512, inputBudget - systemTokens - fileListTokens - PROMPT_OVERHEAD_TOKENS);
        int diffChars = (int) (TokenEstimator.charsForTokens(available) * 0.95);

        StringBuilder prompt = new StringBuilder(Math.min(diffChars + 4_096, 2 * 1024 * 1024));
        prompt.append("Repository: ").append(repository == null || repository.isBlank() ? "(unknown)" : repository)
                .append('\n');
        prompt.append("Changed files: ").append(digest.totalFiles());
        if (digest.skippedFiles() > 0) {
            prompt.append(" (").append(digest.skippedFiles()).append(" skipped)");
        }
        prompt.append('\n');
        prompt.append(fileList(digest));
        prompt.append("\nDiff:\n");

        int remaining = diffChars;
        boolean truncated = digest.truncated();
        for (DiffFile file : digest.files()) {
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            String header = "\n--- " + file.path() + " (" + file.statusLabel() + ") ---\n";
            prompt.append(header);
            remaining -= header.length();
            String text = file.text();
            if (text.length() <= remaining) {
                prompt.append(text);
                remaining -= text.length();
            } else {
                prompt.append(text, 0, Math.max(0, remaining));
                prompt.append("\n... (file diff truncated)\n");
                remaining = 0;
                truncated = true;
            }
        }
        if (truncated) {
            prompt.append("\nNote: the diff was truncated to fit the input limit. Base the message on what is visible.\n");
        }
        prompt.append("\nReturn the commit message now.");
        return prompt.toString();
    }

    private static String fileList(DiffDigest digest) {
        StringBuilder list = new StringBuilder();
        int shown = 0;
        for (DiffFile file : digest.files()) {
            if (shown >= 200) {
                list.append("... and ").append(digest.files().size() - shown).append(" more files\n");
                break;
            }
            list.append("- ").append(file.status()).append(' ').append(file.path()).append('\n');
            shown++;
        }
        return list.toString();
    }
}
