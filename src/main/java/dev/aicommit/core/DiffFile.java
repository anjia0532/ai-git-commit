package dev.aicommit.core;

/** One file in the change digest. */
public record DiffFile(String path, char status, String text, boolean truncated) {
    public DiffFile {
        path = path == null ? "" : path;
        text = text == null ? "" : text;
    }

    public String statusLabel() {
        return switch (status) {
            case 'A' -> "added";
            case 'D' -> "deleted";
            case 'R' -> "renamed";
            default -> "modified";
        };
    }
}
