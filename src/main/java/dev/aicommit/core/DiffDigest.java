package dev.aicommit.core;

import java.util.List;

/** The textual changes handed to the model. */
public record DiffDigest(List<DiffFile> files, int totalFiles, int skippedFiles, boolean truncated) {
    public DiffDigest {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public int totalLines() {
        int lines = 0;
        for (DiffFile file : files) {
            lines += file.text().split("\n", -1).length;
        }
        return lines;
    }
}
