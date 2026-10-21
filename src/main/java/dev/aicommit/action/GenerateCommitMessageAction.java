package dev.aicommit.action;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CommitMessageI;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcs.commit.CommitWorkflowUi;
import dev.aicommit.commit.CommitDiffCollector;
import dev.aicommit.commit.CommitGenerationService;
import dev.aicommit.commit.UnversionedFiles;
import dev.aicommit.core.AiCancelledException;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.AiCommitException;
import dev.aicommit.core.DiffDigest;
import dev.aicommit.core.GenerationResult;
import dev.aicommit.i18n.Messages;
import dev.aicommit.icons.AiCommitIcons;
import dev.aicommit.settings.AiCommitSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Commit toolbar action: generates a message from the changes included in the
 * commit panel and writes it into the commit message editor.
 */
public final class GenerateCommitMessageAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(GenerateCommitMessageAction.class);

    public GenerateCommitMessageAction() {
        super(Messages.tr("action.generate"), Messages.tr("action.description"), AiCommitIcons.GENERATE);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        CommitMessageI control = VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(e.getDataContext());
        boolean available = project != null && control != null
                && !CommitGenerationService.getInstance().isRunning();
        e.getPresentation().setEnabledAndVisible(available);
        e.getPresentation().setText(Messages.tr("action.generate"));
        e.getPresentation().setDescription(Messages.tr("action.description"));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        CommitMessageI control = VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(e.getDataContext());
        if (control == null) {
            com.intellij.openapi.ui.Messages.showErrorDialog(project, Messages.tr("settings.error.noCommit"),
                    Messages.tr("settings.error.title"));
            return;
        }
        List<Change> changes = new ArrayList<>(includedChanges(e));
        CommitWorkflowUi workflowUi = VcsDataKeys.COMMIT_WORKFLOW_UI.getData(e.getDataContext());
        List<FilePath> unversionedPaths = workflowUi == null
                ? List.of()
                : workflowUi.getIncludedUnversionedFiles();
        if (unversionedPaths == null) {
            unversionedPaths = List.of();
        }
        if (changes.isEmpty() && unversionedPaths.isEmpty()) {
            com.intellij.openapi.ui.Messages.showErrorDialog(project, Messages.tr("settings.error.noChanges"),
                    Messages.tr("settings.error.title"));
            return;
        }
        if (CommitGenerationService.getInstance().isRunning()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(project, Messages.tr("settings.error.alreadyRunning"),
                    Messages.tr("settings.error.title"));
            return;
        }

        List<VirtualFile> untracked = List.of();
        if (!unversionedPaths.isEmpty()) {
            try {
                untracked = UnversionedFiles.resolve(project, unversionedPaths);
                UnversionedFiles.addToVcs(project, untracked);
            } catch (AiCommitException error) {
                com.intellij.openapi.ui.Messages.showErrorDialog(project,
                        Messages.tr("error.addToVcs", error.getMessage()),
                        Messages.tr("settings.error.title"));
                return;
            }
        }

        AiCommitConfig config = AiCommitSettings.getInstance().getConfig();
        List<VirtualFile> filesToTrack = untracked;
        Task.Backgroundable task = new Task.Backgroundable(project, Messages.tr("status.generating"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    List<Change> allChanges = new ArrayList<>(changes);
                    if (!filesToTrack.isEmpty()) {
                        indicator.setText(Messages.tr("status.addingFiles", filesToTrack.size()));
                        allChanges.addAll(UnversionedFiles.awaitChanges(project, filesToTrack, indicator));
                    }
                    DiffDigest digest = ReadAction.nonBlocking(
                                    () -> CommitDiffCollector.collect(project, allChanges, config, indicator))
                            .inSmartMode(project)
                            .executeSynchronously();
                    if (digest == null || digest.isEmpty()) {
                        throw new dev.aicommit.core.AiCommitException(
                                Messages.tr("settings.error.noTextChanges"), false, null);
                    }
                    GenerationResult result = CommitGenerationService.getInstance()
                            .generate(project, digest, config, indicator);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed()) {
                            return;
                        }
                        control.setCommitMessage(result.message());
                    }, ModalityState.defaultModalityState());
                } catch (ProcessCanceledException | AiCancelledException cancelled) {
                    // User cancelled: nothing to report.
                } catch (Exception error) {
                    notifyError(project, error);
                }
            }
        };
        task.queue();
    }

    private static List<Change> includedChanges(AnActionEvent event) {
        CommitWorkflowUi workflowUi = VcsDataKeys.COMMIT_WORKFLOW_UI.getData(event.getDataContext());
        if (workflowUi != null) {
            List<Change> included = workflowUi.getIncludedChanges();
            if (included != null && !included.isEmpty()) {
                return included;
            }
        }
        Change[] selected = VcsDataKeys.SELECTED_CHANGES.getData(event.getDataContext());
        if (selected != null && selected.length > 0) {
            return Arrays.asList(selected);
        }
        Change[] changes = VcsDataKeys.CHANGES.getData(event.getDataContext());
        return changes == null ? List.of() : Arrays.asList(changes);
    }

    private static void notifyError(Project project, Throwable error) {
        LOG.warn("Commit message generation failed", error);
        NotificationGroupManager.getInstance()
                .getNotificationGroup("AI Git Commit")
                .createNotification(Messages.tr("settings.error.title"), Messages.errorMessage(error),
                        NotificationType.ERROR)
                .notify(project);
    }
}
