package dev.aicommit.settings;

import com.intellij.ui.table.JBTable;
import dev.aicommit.core.ModelAvailability;
import dev.aicommit.i18n.Messages;

import javax.swing.table.AbstractTableModel;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only table showing the parameters and availability pulled from upstream. */
final class ModelTableModel extends AbstractTableModel {
    record Row(String model, int context, int maxInput, int maxOutput, String reasoning,
               ModelAvailability availability) {
    }

    private List<Row> rows = List.of();

    void setRows(List<Row> rows) {
        this.rows = rows == null ? List.of() : List.copyOf(rows);
        fireTableDataChanged();
    }

    Row rowAt(int modelRow) {
        return modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return 6;
    }

    @Override
    public String getColumnName(int column) {
        return switch (column) {
            case 0 -> Messages.tr("modelTable.model");
            case 1 -> Messages.tr("modelTable.context");
            case 2 -> Messages.tr("modelTable.maxInput");
            case 3 -> Messages.tr("modelTable.maxOutput");
            case 4 -> Messages.tr("modelTable.reasoning");
            default -> Messages.tr("modelTable.status");
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Row row = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.model();
            case 1 -> formatTokens(row.context());
            case 2 -> formatTokens(row.maxInput());
            case 3 -> formatTokens(row.maxOutput());
            case 4 -> row.reasoning();
            default -> row.availability() == null
                    ? Messages.tr("modelHealth.unknown")
                    : Messages.tr("modelHealth." + row.availability().health().id());
        };
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    static String formatTokens(int value) {
        if (value <= 0) {
            return "-";
        }
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format(Locale.ROOT, "%dk", value / 1_000);
        }
        return Integer.toString(value);
    }

    static String reasoningText(List<String> levels, String defaultLevel) {
        if (levels == null || levels.isEmpty()) {
            return "-";
        }
        String text = String.join(", ", levels);
        if (defaultLevel != null && !defaultLevel.isBlank()) {
            text += "  (" + Messages.tr("modelTable.defaultReasoning", defaultLevel) + ")";
        }
        return text;
    }

    static List<Row> rowsFromCatalog(dev.aicommit.core.ModelCatalog catalog,
                                     List<String> configured,
                                     ModelHealthServiceLookup lookup) {
        java.util.LinkedHashMap<String, Row> rows = new java.util.LinkedHashMap<>();
        if (catalog != null) {
            for (dev.aicommit.core.ModelInfo info : catalog.models()) {
                rows.put(info.id(), new Row(info.id(), info.contextWindow(), info.maxInput(), info.maxOutput(),
                        reasoningText(info.reasoningEfforts(), info.defaultReasoningEffort()),
                        lookup.latest(info.id())));
            }
        }
        for (String model : configured) {
            if (model != null && !model.isBlank() && !rows.containsKey(model)) {
                rows.put(model, new Row(model, 0, 0, 0, "-", lookup.latest(model)));
            }
        }
        return new ArrayList<>(rows.values());
    }

    interface ModelHealthServiceLookup {
        ModelAvailability latest(String modelId);
    }
}

/** JTable that exposes the probe detail as a tooltip. */
final class ModelTable extends JBTable {
    ModelTable(ModelTableModel model) {
        super(model);
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int viewRow = rowAtPoint(event.getPoint());
        if (viewRow < 0) {
            return null;
        }
        int modelRow = convertRowIndexToModel(viewRow);
        ModelTableModel.Row row = ((ModelTableModel) getModel()).rowAt(modelRow);
        if (row == null || row.availability() == null || row.availability().detail().isBlank()) {
            return null;
        }
        ModelAvailability availability = row.availability();
        return Messages.tr("modelHealth." + availability.health().id()) + ": " + availability.detail();
    }
}
