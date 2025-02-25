package org.jabref.gui.cleanup;

import java.util.List;
import java.util.Optional;

import org.jabref.gui.DialogService;
import org.jabref.gui.JabRefFrame;
import org.jabref.gui.StateManager;
import org.jabref.gui.actions.ActionHelper;
import org.jabref.gui.actions.SimpleCommand;
import org.jabref.gui.undo.NamedCompound;
import org.jabref.gui.undo.UndoableFieldChange;
import org.jabref.gui.util.BackgroundTask;
import org.jabref.gui.util.TaskExecutor;
import org.jabref.logic.cleanup.CleanupWorker;
import org.jabref.logic.l10n.Localization;
import org.jabref.model.FieldChange;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.BibEntry;
import org.jabref.preferences.CleanupPreferences;
import org.jabref.preferences.PreferencesService;

public class CleanupAction extends SimpleCommand {

    private final JabRefFrame frame;
    private final PreferencesService preferences;
    private final DialogService dialogService;
    private final StateManager stateManager;
    private final TaskExecutor taskExecutor;

    private boolean isCanceled;
    private int modifiedEntriesCount;

    public CleanupAction(JabRefFrame frame,
                         PreferencesService preferences,
                         DialogService dialogService,
                         StateManager stateManager,
                         TaskExecutor taskExecutor) {
        this.frame = frame;
        this.preferences = preferences;
        this.dialogService = dialogService;
        this.stateManager = stateManager;
        this.taskExecutor = taskExecutor;

        this.executable.bind(ActionHelper.needsEntriesSelected(stateManager));
    }

    @Override
    public void execute() {
        if (stateManager.getActiveDatabase().isEmpty()) {
            return;
        }

        if (stateManager.getSelectedEntries().isEmpty()) { // None selected. Inform the user to select entries first.
            dialogService.showInformationDialogAndWait(Localization.lang("Cleanup entry"), Localization.lang("First select entries to clean up."));
            return;
        }

        dialogService.notify(Localization.lang("Doing a cleanup for %0 entries...",
                Integer.toString(stateManager.getSelectedEntries().size())));

        isCanceled = false;
        modifiedEntriesCount = 0;

        CleanupDialog cleanupDialog = new CleanupDialog(
                stateManager.getActiveDatabase().get(),
                preferences.getCleanupPreferences(),
                preferences.getFilePreferences()
        );

        Optional<CleanupPreferences> chosenPreset = dialogService.showCustomDialogAndWait(cleanupDialog);

        chosenPreset.ifPresent(preset -> {
            if (preset.isActive(CleanupPreferences.CleanupStep.RENAME_PDF) && preferences.getAutoLinkPreferences().shouldAskAutoNamingPdfs()) {
                boolean confirmed = dialogService.showConfirmationDialogWithOptOutAndWait(Localization.lang("Autogenerate PDF Names"),
                        Localization.lang("Auto-generating PDF-Names does not support undo. Continue?"),
                        Localization.lang("Autogenerate PDF Names"),
                        Localization.lang("Cancel"),
                        Localization.lang("Do not ask again"),
                        optOut -> preferences.getAutoLinkPreferences().setAskAutoNamingPdfs(!optOut));
                if (!confirmed) {
                    isCanceled = true;
                    return;
                }
            }

            preferences.getCleanupPreferences().setActiveJobs(preset.getActiveJobs());
            preferences.getCleanupPreferences().setFieldFormatterCleanups(preset.getFieldFormatterCleanups());

            BackgroundTask.wrap(() -> cleanup(stateManager.getActiveDatabase().get(), preset))
                          .onSuccess(result -> showResults())
                          .executeWith(taskExecutor);
        });
    }

    /**
     * Runs the cleanup on the entry and records the change.
     */
    private void doCleanup(BibDatabaseContext databaseContext, CleanupPreferences preset, BibEntry entry, NamedCompound ce) {
        // Create and run cleaner
        CleanupWorker cleaner = new CleanupWorker(
                databaseContext,
                preferences.getFilePreferences(),
                preferences.getTimestampPreferences());

        List<FieldChange> changes = cleaner.cleanup(preset, entry);

        // Register undo action
        for (FieldChange change : changes) {
            ce.addEdit(new UndoableFieldChange(change));
        }
    }

    private void showResults() {
        if (isCanceled) {
            return;
        }

        if (modifiedEntriesCount > 0) {
            frame.getCurrentLibraryTab().updateEntryEditorIfShowing();
            frame.getCurrentLibraryTab().markBaseChanged();
        }

        if (modifiedEntriesCount == 0) {
            dialogService.notify(Localization.lang("No entry needed a clean up"));
        } else if (modifiedEntriesCount == 1) {
            dialogService.notify(Localization.lang("One entry needed a clean up"));
        } else {
            dialogService.notify(Localization.lang("%0 entries needed a clean up", Integer.toString(modifiedEntriesCount)));
        }
    }

    private void cleanup(BibDatabaseContext databaseContext, CleanupPreferences cleanupPreferences) {
        for (BibEntry entry : stateManager.getSelectedEntries()) {
            // undo granularity is on entry level
            NamedCompound ce = new NamedCompound(Localization.lang("Cleanup entry"));

            doCleanup(databaseContext, cleanupPreferences, entry, ce);

            ce.end();
            if (ce.hasEdits()) {
                modifiedEntriesCount++;
                frame.getUndoManager().addEdit(ce);
            }
        }
    }
}
