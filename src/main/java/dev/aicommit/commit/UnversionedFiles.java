package dev.aicommit.commit;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import dev.aicommit.core.AiCancelledException;
import dev.aicommit.core.AiCommitException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks unversioned files before a commit message is generated.
 *
 * <p>The commit panel reports untracked files separately from changes. The
 * user asked for them to be added to VCS first, so the generated message sees
 * their content and the subsequent commit includes them.</p>
 */
public final class UnversionedFiles {
    private static final long REGISTRATION_TIMEOUT_MILLIS = 15_000L;

    private UnversionedFiles() {
    }

    /** Resolves the panel's file paths to virtual files. Must run on the EDT. */
    public static List<VirtualFile> resolve(Project project, List<FilePath> paths) throws AiCommitException {
        Set<VirtualFile> files = new LinkedHashSet<>();
        for (FilePath path : paths) {
            if (path == null || path.getPath().isBlank()) {
                continue;
            }
            VirtualFile file = path.getVirtualFile();
            if (file == null || !file.isValid()) {
                file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.getPath());
            }
            if (file == null || !file.isValid()) {
                throw new AiCommitException("Cannot resolve the untracked file: " + path.getPath(), false, null);
            }
            files.add(file);
        }
        if (files.isEmpty()) {
            throw new AiCommitException("No untracked files to add", false, null);
        }
        return new ArrayList<>(files);
    }

    /** Adds the files to the default change list. Must run on the EDT. */
    public static void addToVcs(Project project, List<VirtualFile> files) throws AiCommitException {
        ChangeListManager manager = ChangeListManager.getInstance(project);
        LocalChangeList changeList = manager.getDefaultChangeList();
        if (changeList == null) {
            throw new AiCommitException("There is no active change list to add the files to", false, null);
        }
        try {
            // The public ChangeListManager interface does not expose this
            // operation in every platform version, but every implementation
            // ships it. Calling it reflectively keeps the plugin compatible
            // across 2024.2–2026.x without depending on an internal type.
            java.lang.reflect.Method method = manager.getClass()
                    .getMethod("addUnversionedFiles", LocalChangeList.class, List.class);
            method.invoke(manager, changeList, files);
        } catch (NoSuchMethodException e) {
            throw new AiCommitException("This IDE does not support adding unversioned files programmatically",
                    false, e);
        } catch (ReflectiveOperationException e) {
            throw new AiCommitException("Could not add files to VCS: " + e.getMessage(), true, e);
        }
    }

    /** Waits until the VCS turns the files into real changes, then returns them. */
    public static List<Change> awaitChanges(Project project, List<VirtualFile> files,
                                            ProgressIndicator indicator) throws AiCommitException {
        ChangeListManager manager = ChangeListManager.getInstance(project);
        Set<VirtualFile> pending = new LinkedHashSet<>(files);
        List<Change> changes = new ArrayList<>();
        long deadline = System.currentTimeMillis() + REGISTRATION_TIMEOUT_MILLIS;
        while (!pending.isEmpty()) {
            if (indicator != null && indicator.isCanceled()) {
                throw new AiCancelledException();
            }
            for (VirtualFile file : new ArrayList<>(pending)) {
                Change change = manager.getChange(file);
                if (change != null) {
                    changes.add(change);
                    pending.remove(file);
                }
            }
            if (pending.isEmpty()) {
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                StringBuilder missing = new StringBuilder();
                for (VirtualFile file : pending) {
                    if (missing.length() > 0) {
                        missing.append(", ");
                    }
                    missing.append(file.getPath());
                }
                throw new AiCommitException("Timed out waiting for VCS to track these files: " + missing,
                        true, null);
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiCancelledException();
            }
        }
        return changes;
    }
}
