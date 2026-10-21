package dev.aicommit.startup;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import dev.aicommit.model.ModelWarmupService;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Starts a background model refresh as soon as a project opens. */
public final class ProjectOpenActivity implements ProjectActivity {
    @Override
    public @Nullable Object execute(@NotNull Project project,
                                    @NotNull Continuation<? super Unit> continuation) {
        ModelWarmupService.getInstance().warmUp(project);
        return Unit.INSTANCE;
    }
}
