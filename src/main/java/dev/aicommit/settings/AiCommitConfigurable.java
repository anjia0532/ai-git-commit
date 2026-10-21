package dev.aicommit.settings;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import dev.aicommit.bridge.BridgeStatus;
import dev.aicommit.bridge.OpenCodeZenBridge;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.CommitPromptBuilder;
import dev.aicommit.core.MaxTokensField;
import dev.aicommit.core.ModelAvailability;
import dev.aicommit.core.ModelCatalog;
import dev.aicommit.core.ModelInfo;
import dev.aicommit.core.ProviderPreset;
import dev.aicommit.core.ReasoningEffort;
import dev.aicommit.core.RotationStrategy;
import dev.aicommit.i18n.Messages;
import dev.aicommit.model.ModelCatalogService;
import dev.aicommit.model.ModelHealthService;
import dev.aicommit.model.ProviderEndpoint;
import dev.aicommit.model.ProviderResolver;
import dev.aicommit.publicapi.PublicApiServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.ListCellRenderer;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Settings page under Settings | Tools | AI Git Commit. */
public final class AiCommitConfigurable implements Configurable {
    private JComponent root;
    private AiCommitConfig original;

    private JComboBox<ProviderPreset> presetCombo;
    private JBTextField baseUrlField;
    private JBPasswordField apiKeyField;
    private JBTextField upstreamProxyField;
    private JBLabel keyHint;
    private JBLabel engineStatus;
    private JButton restartButton;
    private JBTextField publicEndpointField;
    private JButton copyEndpointButton;

    private JCheckBox includeFreeCheck;
    private DefaultListModel<String> modelListModel;
    private JList<String> modelList;
    private JBTextField modelInput;
    private JComboBox<RotationStrategy> rotationCombo;
    private JSpinner attemptsSpinner;
    private ModelTableModel parametersModel;
    private ModelTable parametersTable;
    private JButton checkAvailabilityButton;
    private JCheckBox probeOnStartupCheck;

    private JComboBox<String> languageCombo;
    private JComboBox<String> styleCombo;
    private JComboBox<String> uiLanguageCombo;
    private JComboBox<MaxTokensField> maxTokensCombo;
    private JCheckBox includeBodyCheck;
    private JCheckBox useIdeProxyCheck;
    private JSpinner subjectSpinner;
    private JSpinner bodySpinner;
    private JSpinner maxInputSpinner;
    private JSpinner maxOutputSpinner;
    private JSpinner temperatureSpinner;
    private JSpinner timeoutSpinner;
    private JSpinner maxFilesSpinner;
    private JComboBox<ReasoningEffort> reasoningCombo;

    private JBTextArea promptArea;
    private JBTextArea customArea;
    private JBTextField excludeField;

    @Override
    public String getDisplayName() {
        return Messages.tr("settings.displayName");
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (root == null) {
            root = buildPanel();
            reset();
            refreshEngineStatus();
        }
        return root;
    }

    @Override
    public boolean isModified() {
        if (original == null) {
            return false;
        }
        if (!typedApiKey().isBlank()) {
            return true;
        }
        String ui = (String) uiLanguageCombo.getSelectedItem();
        String storedUi = AiCommitSettings.getInstance().getState().uiLanguage;
        boolean probe = probeOnStartupCheck != null && probeOnStartupCheck.isSelected();
        boolean storedProbe = AiCommitSettings.getInstance().getState().probeOnStartup;
        return !formConfig().equals(original) || !Objects.equals(ui, storedUi) || probe != storedProbe;
    }

    @Override
    public void apply() throws ConfigurationException {
        AiCommitConfig config = formConfig();
        if (!config.isBuiltInEngine() && config.baseUrl().isBlank()) {
            throw new ConfigurationException(Messages.tr("settings.validation.baseUrl"));
        }
        if (config.modelIds().isEmpty() && !config.includeFreeModels()) {
            throw new ConfigurationException(Messages.tr("settings.validation.models"));
        }
        AiCommitSettings settings = AiCommitSettings.getInstance();
        settings.updateFrom(config, typedApiKey());
        settings.getState().uiLanguage = (String) uiLanguageCombo.getSelectedItem();
        settings.getState().probeOnStartup = probeOnStartupCheck.isSelected();
        Messages.invalidate();
        updateActionText();
        original = settings.getConfig();
        apiKeyField.setText("");
        updateKeyHint();
    }

    @Override
    public void reset() {
        AiCommitSettings settings = AiCommitSettings.getInstance();
        original = settings.getConfig();
        AiCommitConfig config = original;

        presetCombo.setSelectedItem(config.preset());
        baseUrlField.setText(config.baseUrl());
        apiKeyField.setText("");
        upstreamProxyField.setText(config.upstreamProxy());
        includeFreeCheck.setSelected(config.includeFreeModels());
        modelListModel.clear();
        for (String model : config.modelIds()) {
            modelListModel.addElement(model);
        }
        rotationCombo.setSelectedItem(config.rotation());
        attemptsSpinner.setValue(config.maxAttempts());
        languageCombo.setSelectedItem(config.language());
        styleCombo.setSelectedItem(config.style());
        includeBodyCheck.setSelected(config.includeBody());
        subjectSpinner.setValue(config.subjectLimit());
        bodySpinner.setValue(config.bodyWidth());
        reasoningCombo.setSelectedItem(config.reasoningEffort());
        maxInputSpinner.setValue(config.maxInputTokens());
        maxOutputSpinner.setValue(config.maxOutputTokens());
        temperatureSpinner.setValue(config.temperature());
        timeoutSpinner.setValue(config.timeoutSeconds());
        maxTokensCombo.setSelectedItem(config.maxTokensField());
        maxFilesSpinner.setValue(config.maxFiles());
        excludeField.setText(config.excludePatterns());
        promptArea.setText(config.promptTemplate());
        customArea.setText(config.customInstructions());
        useIdeProxyCheck.setSelected(config.useIdeProxy());
        uiLanguageCombo.setSelectedItem(settings.getState().uiLanguage);
        probeOnStartupCheck.setSelected(settings.getState().probeOnStartup);
        updateEnabled();
        updateKeyHint();
        refreshModelTable();
    }

    @Override
    public void disposeUIResources() {
        root = null;
        original = null;
    }

    private JComponent buildPanel() {
        presetCombo = new JComboBox<>(ProviderPreset.values());
        presetCombo.setRenderer(renderer(Messages::presetName));
        presetCombo.addActionListener(e -> {
            ProviderPreset selected = (ProviderPreset) presetCombo.getSelectedItem();
            if (selected == null) {
                return;
            }
            if (!selected.defaultBaseUrl().isBlank() && baseUrlField.getText().isBlank()) {
                baseUrlField.setText(selected.defaultBaseUrl());
            }
            if (selected.isBuiltIn() && modelListModel.isEmpty()) {
                for (String model : selected.defaultModels()) {
                    modelListModel.addElement(model);
                }
            }
            updateEnabled();
        });

        baseUrlField = new JBTextField();
        baseUrlField.getEmptyText().setText("https://api.example.com/v1");
        apiKeyField = new JBPasswordField();
        keyHint = new JBLabel();
        keyHint.setForeground(JBColor.GRAY);
        upstreamProxyField = new JBTextField();
        upstreamProxyField.getEmptyText().setText("http://127.0.0.1:7890");

        JButton testButton = new JButton(Messages.tr("settings.provider.test"));
        testButton.addActionListener(e -> testConnection());
        JButton refreshButton = new JButton(Messages.tr("settings.provider.refresh"));
        refreshButton.addActionListener(e -> loadModels());
        restartButton = new JButton(Messages.tr("settings.provider.restart"));
        restartButton.addActionListener(e -> restartEngine());
        engineStatus = new JBLabel();

        JPanel providerButtons = row(testButton, refreshButton);
        JPanel engineRow = row(engineStatus, restartButton);

        publicEndpointField = new JBTextField();
        publicEndpointField.setEditable(false);
        publicEndpointField.setColumns(48);
        copyEndpointButton = new JButton(Messages.tr("settings.provider.copyEndpoint"));
        copyEndpointButton.addActionListener(e -> copyPublicEndpoint());
        JPanel publicEndpointRow = row(publicEndpointField, copyEndpointButton);

        includeFreeCheck = new JCheckBox(Messages.tr("settings.models.includeFree"));
        modelListModel = new DefaultListModel<>();
        modelList = new JBList<>(modelListModel);
        modelList.setVisibleRowCount(5);
        modelList.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JBScrollPane modelScroll = new JBScrollPane(modelList);
        modelScroll.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(110)));

        modelInput = new JBTextField();
        modelInput.getEmptyText().setText(Messages.tr("settings.models.placeholder"));
        JButton addModel = new JButton(Messages.tr("settings.models.add"));
        addModel.addActionListener(e -> addModel());
        JButton removeModel = new JButton(Messages.tr("settings.models.remove"));
        removeModel.addActionListener(e -> removeSelectedModels());
        JButton loadModelButton = new JButton(Messages.tr("settings.models.loadFree"));
        loadModelButton.addActionListener(e -> loadModels());
        JPanel modelButtons = row(modelInput, addModel, removeModel, loadModelButton);

        parametersModel = new ModelTableModel();
        parametersTable = new ModelTable(parametersModel);
        parametersTable.setAutoCreateRowSorter(true);
        parametersTable.setFillsViewportHeight(true);
        parametersTable.setRowSelectionAllowed(false);
        parametersTable.setShowGrid(false);
        parametersTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(250));
        JScrollPane parameterScroll = new JBScrollPane(parametersTable);
        parameterScroll.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(150)));
        checkAvailabilityButton = new JButton(Messages.tr("settings.models.checkAvailability"));
        checkAvailabilityButton.addActionListener(e -> checkAvailability());

        rotationCombo = new JComboBox<>(RotationStrategy.values());
        rotationCombo.setRenderer(renderer(Messages::rotationName));
        attemptsSpinner = spinner(3, 1, 10, 1);

        languageCombo = new JComboBox<>(new String[]{"auto", "en", "zh"});
        languageCombo.setRenderer(renderer(Messages::languageName));
        styleCombo = new JComboBox<>(new String[]{"conventional", "plain"});
        styleCombo.setRenderer(renderer(id -> Messages.tr("settings.generation.style." + id)));
        uiLanguageCombo = new JComboBox<>(new String[]{"auto", "en", "zh"});
        uiLanguageCombo.setRenderer(renderer(Messages::uiLanguageName));
        includeBodyCheck = new JCheckBox(Messages.tr("settings.generation.includeBody"));
        subjectSpinner = spinner(72, 20, 200, 1);
        bodySpinner = spinner(72, 40, 200, 1);

        reasoningCombo = new JComboBox<>(ReasoningEffort.values());
        reasoningCombo.setRenderer(renderer(Messages::reasoningName));
        maxInputSpinner = spinner(32_000, 1_024, 4_000_000, 1_024);
        maxOutputSpinner = spinner(1_024, 64, 200_000, 64);
        temperatureSpinner = spinner(0.2, 0.0, 2.0, 0.1);
        timeoutSpinner = spinner(150, 5, 900, 5);
        maxTokensCombo = new JComboBox<>(MaxTokensField.values());
        maxTokensCombo.setRenderer(renderer(Messages::maxTokensName));

        promptArea = new JBTextArea(8, 80);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        JScrollPane promptScroll = new JBScrollPane(promptArea);
        promptScroll.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(170)));
        JButton resetPrompt = new JButton(Messages.tr("settings.prompt.reset"));
        resetPrompt.addActionListener(e -> promptArea.setText(CommitPromptBuilder.DEFAULT_SYSTEM_PROMPT));

        customArea = new JBTextArea(3, 80);
        customArea.setLineWrap(true);
        customArea.setWrapStyleWord(true);
        JScrollPane customScroll = new JBScrollPane(customArea);
        customScroll.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(80)));

        maxFilesSpinner = spinner(40, 1, 500, 1);
        excludeField = new JBTextField();
        useIdeProxyCheck = new JCheckBox(Messages.tr("settings.advanced.useIdeProxy"));

        FormBuilder form = FormBuilder.createFormBuilder();
        form.addComponent(new TitledSeparator(Messages.tr("settings.provider.section")));
        form.addLabeledComponent(Messages.tr("settings.provider.preset"), presetCombo);
        form.addLabeledComponent(Messages.tr("settings.provider.baseUrl"), baseUrlField);
        form.addLabeledComponent(Messages.tr("settings.provider.apiKey"), apiKeyField);
        form.addComponent(keyHint);
        form.addComponent(providerButtons);
        form.addLabeledComponent(Messages.tr("settings.provider.upstreamProxy"), upstreamProxyField);
        JBLabel proxyHint = new JBLabel(Messages.tr("settings.provider.upstreamProxyHint"));
        proxyHint.setForeground(JBColor.GRAY);
        form.addComponent(proxyHint);
        form.addComponent(engineRow);
        form.addLabeledComponent(Messages.tr("settings.provider.publicEndpoint"), publicEndpointRow);
        JBLabel publicEndpointHint = new JBLabel(Messages.tr("settings.provider.publicEndpointHint"));
        publicEndpointHint.setForeground(JBColor.GRAY);
        form.addComponent(publicEndpointHint);
        JBLabel builtInNote = new JBLabel(Messages.tr("settings.provider.builtInNote"));
        builtInNote.setForeground(JBColor.GRAY);
        form.addComponent(builtInNote);

        form.addComponent(new TitledSeparator(Messages.tr("settings.models.section")));
        form.addComponent(includeFreeCheck);
        form.addLabeledComponent(Messages.tr("settings.models.list"), modelScroll);
        form.addComponent(modelButtons);
        form.addLabeledComponent(Messages.tr("settings.models.parameters"), parameterScroll);
        JBLabel parameterHint = new JBLabel(Messages.tr("settings.models.parametersHint"));
        parameterHint.setForeground(JBColor.GRAY);
        form.addComponent(parameterHint);
        form.addComponent(row(checkAvailabilityButton));
        form.addLabeledComponent(Messages.tr("settings.models.rotation"), rotationCombo);
        form.addLabeledComponent(Messages.tr("settings.models.attempts"), attemptsSpinner);

        form.addComponent(new TitledSeparator(Messages.tr("settings.generation.section")));
        form.addLabeledComponent(Messages.tr("settings.generation.language"), languageCombo);
        form.addLabeledComponent(Messages.tr("settings.generation.style"), styleCombo);
        form.addComponent(includeBodyCheck);
        form.addLabeledComponent(Messages.tr("settings.generation.subjectLimit"), subjectSpinner);
        form.addLabeledComponent(Messages.tr("settings.generation.bodyWidth"), bodySpinner);
        form.addLabeledComponent(Messages.tr("settings.generation.uiLanguage"), uiLanguageCombo);
        JBLabel uiHint = new JBLabel(Messages.tr("settings.generation.uiLanguageHint"));
        uiHint.setForeground(JBColor.GRAY);
        form.addComponent(uiHint);

        form.addComponent(new TitledSeparator(Messages.tr("settings.prompt.section")));
        form.addLabeledComponent(Messages.tr("settings.prompt.template"), promptScroll);
        JBLabel promptHint = new JBLabel(Messages.tr("settings.prompt.templateHint"));
        promptHint.setForeground(JBColor.GRAY);
        form.addComponent(promptHint);
        form.addComponent(row(resetPrompt));
        form.addLabeledComponent(Messages.tr("settings.prompt.custom"), customScroll);
        JBLabel customHint = new JBLabel(Messages.tr("settings.prompt.customHint"));
        customHint.setForeground(JBColor.GRAY);
        form.addComponent(customHint);

        form.addComponent(new TitledSeparator(Messages.tr("settings.params.section")));
        form.addLabeledComponent(Messages.tr("settings.params.reasoning"), reasoningCombo);
        form.addLabeledComponent(Messages.tr("settings.params.maxInput"), maxInputSpinner);
        form.addLabeledComponent(Messages.tr("settings.params.maxOutput"), maxOutputSpinner);
        form.addLabeledComponent(Messages.tr("settings.params.temperature"), temperatureSpinner);
        form.addLabeledComponent(Messages.tr("settings.params.timeout"), timeoutSpinner);
        form.addLabeledComponent(Messages.tr("settings.params.maxTokensField"), maxTokensCombo);

        form.addComponent(new TitledSeparator(Messages.tr("settings.diff.section")));
        form.addLabeledComponent(Messages.tr("settings.diff.maxFiles"), maxFilesSpinner);
        form.addLabeledComponent(Messages.tr("settings.diff.exclude"), excludeField);
        JBLabel excludeHint = new JBLabel(Messages.tr("settings.diff.excludeHint"));
        excludeHint.setForeground(JBColor.GRAY);
        form.addComponent(excludeHint);

        form.addComponent(new TitledSeparator(Messages.tr("settings.advanced.section")));
        probeOnStartupCheck = new JCheckBox(Messages.tr("settings.models.probeOnStartup"));
        form.addComponent(probeOnStartupCheck);
        form.addComponent(useIdeProxyCheck);
        form.addComponent(new JBLabel(" "));

        JBScrollPane scroll = new JBScrollPane(form.getPanel());
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel row(JComponent... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(JBUI.scale(4), 0, 0, 0));
        for (JComponent component : components) {
            panel.add(component);
        }
        return panel;
    }

    private static JSpinner spinner(Number value, Comparable<?> min, Comparable<?> max, Number step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner);
        editor.getTextField().setColumns(10);
        spinner.setEditor(editor);
        return spinner;
    }

    private static <T> ListCellRenderer<? super T> renderer(Function<T, String> names) {
        return new SimpleListCellRenderer<>() {
            @Override
            public void customize(@NotNull JList<? extends T> list, @Nullable T value, int index,
                                  boolean selected, boolean hasFocus) {
                setText(value == null ? "" : names.apply(value));
            }
        };
    }

    private void addModel() {
        String value = modelInput.getText().trim();
        if (value.isEmpty()) {
            return;
        }
        if (!modelListModel.contains(value)) {
            modelListModel.addElement(value);
        }
        modelInput.setText("");
    }

    private void removeSelectedModels() {
        for (String selected : modelList.getSelectedValuesList()) {
            modelListModel.removeElement(selected);
        }
    }

    private AiCommitConfig formConfig() {
        AiCommitSettings settings = AiCommitSettings.getInstance();
        ProviderPreset preset = (ProviderPreset) presetCombo.getSelectedItem();
        String baseUrl = baseUrlField.getText().trim();
        String typed = typedApiKey();
        String apiKey = typed;
        if (apiKey.isBlank()) {
            apiKey = preset != null && preset.isBuiltIn() ? "" : settings.storedKey(baseUrl);
        }
        List<String> models = Collections.list(modelListModel.elements());
        return AiCommitConfig.builder()
                .preset(preset == null ? ProviderPreset.CUSTOM : preset)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelIds(models)
                .includeFreeModels(includeFreeCheck.isSelected())
                .rotation((RotationStrategy) rotationCombo.getSelectedItem())
                .reasoningEffort((ReasoningEffort) reasoningCombo.getSelectedItem())
                .maxInputTokens(number(maxInputSpinner))
                .maxOutputTokens(number(maxOutputSpinner))
                .temperature(((Number) temperatureSpinner.getValue()).doubleValue())
                .timeoutSeconds(number(timeoutSpinner))
                .maxAttempts(number(attemptsSpinner))
                .useIdeProxy(useIdeProxyCheck.isSelected())
                .language((String) languageCombo.getSelectedItem())
                .style((String) styleCombo.getSelectedItem())
                .includeBody(includeBodyCheck.isSelected())
                .subjectLimit(number(subjectSpinner))
                .bodyWidth(number(bodySpinner))
                .customInstructions(customArea.getText())
                .promptTemplate(promptArea.getText())
                .maxFiles(number(maxFilesSpinner))
                .excludePatterns(excludeField.getText())
                .maxTokensField((MaxTokensField) maxTokensCombo.getSelectedItem())
                .upstreamProxy(upstreamProxyField.getText())
                .build();
    }

    private static int number(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    private String typedApiKey() {
        return apiKeyField == null ? "" : new String(apiKeyField.getPassword()).trim();
    }

    private void updateEnabled() {
        ProviderPreset preset = (ProviderPreset) presetCombo.getSelectedItem();
        boolean builtIn = preset != null && preset.isBuiltIn();
        baseUrlField.setEnabled(!builtIn);
        apiKeyField.setEnabled(!builtIn);
        restartButton.setEnabled(builtIn);
        keyHint.setVisible(!builtIn);
        if (builtIn) {
            baseUrlField.setText("");
        } else if (baseUrlField.getText().isBlank() && preset != null) {
            baseUrlField.setText(preset.defaultBaseUrl());
        }
        refreshPublicEndpoint();
    }

    private void updateKeyHint() {
        ProviderPreset preset = (ProviderPreset) presetCombo.getSelectedItem();
        if (preset != null && preset.isBuiltIn()) {
            keyHint.setText(Messages.tr("settings.provider.managed"));
            return;
        }
        String baseUrl = baseUrlField.getText().trim();
        keyHint.setText(AiCommitSettings.getInstance().hasStoredKey(baseUrl)
                ? "****** " + Messages.tr("settings.provider.apiKeyHint")
                : Messages.tr("settings.provider.apiKeyHint"));
    }

    private void updateActionText() {
        AnAction action = ActionManager.getInstance().getAction("AiCommit.GenerateCommitMessage");
        if (action != null) {
            action.getTemplatePresentation().setText(Messages.tr("action.generate"));
            action.getTemplatePresentation().setDescription(Messages.tr("action.description"));
        }
    }

    private void testConnection() {
        AiCommitConfig config = formConfig();
        FetchOutcome outcome = fetchInBackground(config);
        if (outcome.error != null) {
            com.intellij.openapi.ui.Messages.showErrorDialog(root, Messages.errorMessage(outcome.error),
                    Messages.tr("settings.error.title"));
            refreshEngineStatus();
            return;
        }
        ModelCatalog catalog = outcome.catalog;
        int total = catalog.models().size();
        int free = catalog.freeModelIds().size();
        com.intellij.openapi.ui.Messages.showInfoMessage(root,
                Messages.tr("settings.provider.testOk", total, free),
                Messages.tr("settings.error.title"));
        refreshModelTable();
        refreshEngineStatus();
    }

    private void loadModels() {
        AiCommitConfig config = formConfig();
        FetchOutcome outcome = fetchInBackground(config);
        if (outcome.error != null) {
            com.intellij.openapi.ui.Messages.showErrorDialog(root, Messages.errorMessage(outcome.error),
                    Messages.tr("settings.error.title"));
            return;
        }
        List<String> ids = config.isBuiltInEngine()
                ? outcome.catalog.freeModelIds()
                : outcome.catalog.allModelIds();
        if (ids.isEmpty()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(root,
                    Messages.tr("settings.provider.noModels"), Messages.tr("settings.error.title"));
            return;
        }
        for (String id : ids) {
            if (!modelListModel.contains(id)) {
                modelListModel.addElement(id);
            }
        }
        refreshModelTable();
        com.intellij.openapi.ui.Messages.showInfoMessage(root,
                Messages.tr("settings.provider.loaded", ids.size()), Messages.tr("settings.error.title"));
    }

    private void checkAvailability() {
        AiCommitConfig config = formConfig();
        FetchOutcome outcome = new FetchOutcome();
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            com.intellij.openapi.progress.ProgressIndicator indicator =
                    ProgressManager.getInstance().getProgressIndicator();
            java.util.function.BooleanSupplier cancelled =
                    indicator == null ? () -> false : indicator::isCanceled;
            try {
                ProviderEndpoint endpoint = ProviderResolver.resolve(config);
                outcome.endpoint = endpoint;
                outcome.catalog = ModelCatalogService.getInstance()
                        .get(config, endpoint.baseUrl(), endpoint.apiKey(), true, cancelled);
                List<String> models = outcome.catalog.effectiveModelIds(config.modelIds(),
                        config.includeFreeModels());
                if (models.isEmpty()) {
                    models = config.modelIds();
                }
                if (!models.isEmpty()) {
                    ModelHealthService.getInstance().clear();
                    Map<String, ModelAvailability> health = ModelHealthService.getInstance()
                            .ensureFresh(config, endpoint.baseUrl(), endpoint.apiKey(), models, 0,
                                    cancelled, indicator);
                    outcome.checked = health.size();
                    outcome.available = (int) health.values().stream()
                            .filter(ModelAvailability::usable).count();
                }
            } catch (Throwable t) {
                outcome.error = t;
            }
        }, Messages.tr("settings.models.checkAvailability"), true, null);
        if (outcome.error != null) {
            com.intellij.openapi.ui.Messages.showErrorDialog(root, Messages.errorMessage(outcome.error),
                    Messages.tr("settings.error.title"));
            return;
        }
        refreshModelTable();
        com.intellij.openapi.ui.Messages.showInfoMessage(root,
                Messages.tr("settings.models.availability", outcome.checked, outcome.available),
                Messages.tr("settings.error.title"));
    }

    private static final class FetchOutcome {
        private ModelCatalog catalog = ModelCatalog.empty();
        private ProviderEndpoint endpoint;
        private Throwable error;
        private int checked;
        private int available;
    }

    private FetchOutcome fetchInBackground(AiCommitConfig config) {
        FetchOutcome outcome = new FetchOutcome();
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            try {
                com.intellij.openapi.progress.ProgressIndicator indicator =
                        ProgressManager.getInstance().getProgressIndicator();
                java.util.function.BooleanSupplier cancelled =
                        indicator == null ? () -> false : indicator::isCanceled;
                ProviderEndpoint endpoint = ProviderResolver.resolve(config);
                outcome.endpoint = endpoint;
                outcome.catalog = ModelCatalogService.getInstance()
                        .get(config, endpoint.baseUrl(), endpoint.apiKey(), true, cancelled);
            } catch (Throwable t) {
                outcome.error = t;
            }
        }, Messages.tr("settings.provider.loading"), true, null);
        return outcome;
    }

    private void refreshModelTable() {
        if (parametersModel == null) {
            return;
        }
        List<String> configured = Collections.list(modelListModel.elements());
        ModelCatalogService.Cached latest = ModelCatalogService.getInstance().latest();
        ModelCatalog catalog = latest == null ? ModelCatalog.empty() : latest.catalog();
        parametersModel.setRows(ModelTableModel.rowsFromCatalog(catalog, configured,
                ModelHealthService.getInstance()::latest));
    }

    private void refreshEngineStatus() {
        BridgeStatus status = OpenCodeZenBridge.getInstance().status();
        String detail = status.running()
                ? Messages.tr("settings.provider.engine.running", status.port())
                : Messages.tr("settings.provider.engine.stopped");
        engineStatus.setText(Messages.tr("settings.provider.engine", detail));
        engineStatus.setToolTipText(status.logFile() == null ? null : status.logFile().toString());
        refreshPublicEndpoint();
    }

    private void refreshPublicEndpoint() {
        if (publicEndpointField == null || copyEndpointButton == null) {
            return;
        }
        ProviderPreset preset = (ProviderPreset) presetCombo.getSelectedItem();
        boolean builtIn = preset != null && preset.isBuiltIn();
        String url = builtIn ? PublicApiServer.getInstance().currentBaseUrl() : null;
        publicEndpointField.setText(url == null ? "" : url);
        copyEndpointButton.setEnabled(builtIn);
    }

    private void copyPublicEndpoint() {
        AiCommitConfig config = formConfig();
        if (!config.isBuiltInEngine()) {
            return;
        }
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            try {
                ProviderEndpoint endpoint = ProviderResolver.resolve(config);
                PublicApiServer.getInstance().ensureStarted(config, endpoint);
                result.set(PublicApiServer.getInstance().currentBaseUrl());
            } catch (Throwable t) {
                error.set(t);
            }
        }, Messages.tr("settings.provider.copyEndpoint"), true, null);
        if (error.get() != null) {
            com.intellij.openapi.ui.Messages.showErrorDialog(root, Messages.errorMessage(error.get()),
                    Messages.tr("settings.error.title"));
            return;
        }
        String url = result.get();
        if (url == null || url.isBlank()) {
            com.intellij.openapi.ui.Messages.showErrorDialog(root,
                    Messages.tr("settings.provider.publicEndpointUnavailable"),
                    Messages.tr("settings.error.title"));
            return;
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(url));
        publicEndpointField.setText(url);
        com.intellij.openapi.ui.Messages.showInfoMessage(root,
                Messages.tr("settings.provider.publicEndpointCopied", url),
                Messages.tr("settings.error.title"));
        refreshEngineStatus();
    }

    private void restartEngine() {
        AiCommitConfig config = formConfig();
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            OpenCodeZenBridge.getInstance().restart();
            if (config.isBuiltInEngine()) {
                try {
                    ProviderEndpoint endpoint = ProviderResolver.resolve(config);
                    PublicApiServer.getInstance().ensureStarted(config, endpoint);
                } catch (Throwable ignored) {
                    // The status label below reports whether the engine is up.
                }
            }
        }, Messages.tr("settings.provider.restart"), true, null);
        refreshEngineStatus();
    }
}
