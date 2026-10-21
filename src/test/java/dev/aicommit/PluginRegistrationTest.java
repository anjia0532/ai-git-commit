package dev.aicommit;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.aicommit.bridge.OpenCodeZenBridge;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.settings.AiCommitConfigurable;
import dev.aicommit.settings.AiCommitSettings;

import java.lang.reflect.Method;
import java.util.List;

/** Loads the plugin descriptor in a platform test application. */
public class PluginRegistrationTest extends BasePlatformTestCase {
    public void testActionIsRegistered() {
        AnAction action = ActionManager.getInstance().getAction("AiCommit.GenerateCommitMessage");
        assertNotNull("Commit toolbar action is missing from plugin.xml", action);
        assertNotNull(action.getTemplatePresentation().getIcon());
    }

    public void testSettingsServiceAndConfigurable() {
        AiCommitSettings settings = ApplicationManager.getApplication().getService(AiCommitSettings.class);
        assertNotNull(settings);
        AiCommitConfig config = settings.getConfig();
        assertNotNull(config);
        assertFalse(config.promptTemplate().isBlank());

        AiCommitConfigurable configurable = new AiCommitConfigurable();
        assertNotNull(configurable.createComponent());
        assertEquals("AI Git Commit", configurable.getDisplayName());
        configurable.disposeUIResources();
    }

    public void testBridgeServiceCanBeCreated() {
        assertNotNull(ApplicationManager.getApplication().getService(OpenCodeZenBridge.class));
    }

    public void testStartupActivityClassLoads() throws Exception {
        assertNotNull(Class.forName("dev.aicommit.startup.ProjectOpenActivity").getDeclaredConstructor());
    }

    public void testChangeListManagerSupportsAddingUnversionedFiles() throws Exception {
        Object manager = ChangeListManager.getInstance(getProject());
        Method method = manager.getClass()
                .getMethod("addUnversionedFiles", LocalChangeList.class, List.class);
        assertNotNull(method);
    }
}
