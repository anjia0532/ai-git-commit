package dev.aicommit.commit;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.DiffDigest;
import dev.aicommit.core.DiffFile;
import dev.aicommit.core.TextDiffRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Turns the changes selected in the commit panel into a compact diff. */
public final class CommitDiffCollector {
    private static final int MAX_LINES_PER_FILE = 1_600;

    private CommitDiffCollector() {
    }

    public static DiffDigest collect(Project project, List<Change> changes, AiCommitConfig config,
                                     ProgressIndicator indicator) {
        List<Change> sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparing(change -> pathOf(project, change), String.CASE_INSENSITIVE_ORDER));
        GlobMatcher excludes = GlobMatcher.compile(config.excludePatterns());
        List<DiffFile> files = new ArrayList<>();
        int skipped = 0;
        boolean truncated = false;

        for (Change change : sorted) {
            if (indicator != null && indicator.isCanceled()) {
                break;
            }
            String path = pathOf(project, change);
            if (path.isBlank() || excludes.matches(path)) {
                skipped++;
                continue;
            }
            if (files.size() >= config.maxFiles()) {
                truncated = true;
                break;
            }
            var file = change.getVirtualFile();
            if (file != null && file.getFileType().isBinary()) {
                skipped++;
                continue;
            }
            if (file != null && file.getLength() > 5L * 1024 * 1024) {
                skipped++;
                continue;
            }
            try {
                String before = content(change.getBeforeRevision());
                String after = content(change.getAfterRevision());
                if (before.isEmpty() && after.isEmpty()) {
                    skipped++;
                    continue;
                }
                String text = TextDiffRenderer.render(statusOf(change), before, after, MAX_LINES_PER_FILE);
                files.add(new DiffFile(path, statusOf(change), text,
                        text.contains("(more ") || text.contains("more lines")));
            } catch (Exception e) {
                skipped++;
            }
        }
        return new DiffDigest(files, sorted.size(), skipped, truncated);
    }

    private static String content(ContentRevision revision) {
        if (revision == null) {
            return "";
        }
        try {
            String content = revision.getContent();
            return content == null ? "" : content;
        } catch (Exception e) {
            return "";
        }
    }

    static String pathOf(Project project, Change change) {
        String absolute = "";
        if (change.getAfterRevision() != null && change.getAfterRevision().getFile() != null) {
            absolute = change.getAfterRevision().getFile().getPath();
        } else if (change.getBeforeRevision() != null && change.getBeforeRevision().getFile() != null) {
            absolute = change.getBeforeRevision().getFile().getPath();
        }
        if (!absolute.isBlank() && project != null && project.getBasePath() != null) {
            try {
                var base = java.nio.file.Path.of(project.getBasePath());
                var file = java.nio.file.Path.of(absolute);
                if (file.startsWith(base)) {
                    return base.relativize(file).toString().replace('\\', '/');
                }
            } catch (RuntimeException ignored) {
                // Different roots or an invalid path: keep the absolute path.
            }
        }
        return absolute;
    }

    private static char statusOf(Change change) {
        if (change.getType() == null) {
            return 'M';
        }
        return switch (change.getType()) {
            case NEW -> 'A';
            case DELETED -> 'D';
            case MOVED -> 'R';
            default -> 'M';
        };
    }
}
