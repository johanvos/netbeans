package com.gluonhq.netbeans.nbfx.navigator;

import com.gluonhq.netbeans.nbfx.api.ActionIds;
import com.gluonhq.netbeans.nbfx.api.ActionRegistry;
import com.gluonhq.netbeans.nbfx.api.Command;
import com.gluonhq.netbeans.nbfx.api.EditorContext;
import com.gluonhq.netbeans.nbfx.api.OpenProject;
import com.gluonhq.netbeans.nbfx.file.actions.DesktopActions;
import com.gluonhq.netbeans.nbfx.file.actions.FileClipboardActions;
import com.gluonhq.netbeans.nbfx.navigator.utils.NavigatorIcons;
import com.gluonhq.netbeans.nbfx.navigator.utils.PackageScanner;
import com.gluonhq.netbeans.nbfx.navigator.utils.Projects;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;

/**
 * Builds the shared navigator {@link ContextMenu}, driven by a {@link Host} that adapts it to a
 * particular tree (the logical Project view or the physical Files view). The menu content is the
 * same for both views; the host only supplies how a node maps to a {@link FileObject}, whether it is
 * the tree root, whether it can be deleted, and how to delete/open the backing files.
 *
 * @param <T> the tree item value type
 */
final class NavigatorContextMenuFactory<T> {

    /** View-specific hooks the shared menu is built on top of. */
    interface Host<T> {
        /** The {@link FileObject} backing {@code item}, or {@code null} when there is none. */
        FileObject fileOf(TreeItem<T> item);

        /** Whether {@code item} is the tree's root node (offers "Close" instead of clipboard/delete). */
        boolean isRoot(TreeItem<T> item);

        /** Whether {@code item} can be cut/copied/deleted. */
        boolean isDeletable(TreeItem<T> item);

        /** Confirms and deletes the given items, updating the tree. */
        void deleteItems(List<TreeItem<T>> items);

        /**
         * Opens the given files in the editor. Both views open files identically, so this default
         * suffices; a view only needs to override it if its open behaviour ever diverges.
         */
        default void openFiles(List<FileObject> files) {
            for (FileObject fo : files) {
                PackageScanner.openFile(fo, NavigatorIcons.getFileIconName(fo));
            }
        }
    }

    private final Host<T> host;

    NavigatorContextMenuFactory(Host<T> host) {
        this.host = host;
    }

    /**
     * Builds the context menu for {@code clicked} within the current {@code selection}, or
     * {@code null} when there is nothing actionable.
     */
    ContextMenu create(TreeItem<T> clicked, List<TreeItem<T>> selection) {
        if (selection != null && selection.size() > 1) {
            return createMany(selection);
        }
        FileObject fo = clicked == null ? null : host.fileOf(clicked);
        if (fo == null) {
            return null;
        }
        boolean isFile = !fo.isFolder();
        boolean canCutCopy = host.isDeletable(clicked);
        ContextMenu menu = new ContextMenu();

        if (isFile) {
            if (PackageScanner.isOpenable(fo)) {
                menu.getItems().addAll(
                        openItem(clicked, fo),
                        new SeparatorMenuItem());
            }
            addClipboardItems(menu, fo, canCutCopy);
            menu.getItems().addAll(
                    new SeparatorMenuItem(),
                    deleteItem(clicked),
                    new SeparatorMenuItem());
        } else if (host.isRoot(clicked)) {
            menu.getItems().addAll(
                    saveItem(fo),
                    new SeparatorMenuItem(),
                    closeItem(fo));
            if (Projects.openProjects().size() > 1) {
                menu.getItems().add(closeAllItem());
            }
            menu.getItems().add(new SeparatorMenuItem());
        } else {
            // A branch (folder / package).
            menu.getItems().addAll(
                    newItem(),
                    new SeparatorMenuItem());
            addClipboardItems(menu, fo, canCutCopy);
            menu.getItems().add(new SeparatorMenuItem());
            if (canCutCopy) {
                menu.getItems().addAll(
                        deleteItem(clicked),
                        new SeparatorMenuItem());
            }
        }
        // Terminal / file-manager actions are available on every node.
        menu.getItems().addAll(
                openInTerminalItem(fo),
                showInFileManagerItem(fo));
        return menu;
    }

    /** Builds a batch menu that operates on all selected items. */
    private ContextMenu createMany(List<TreeItem<T>> selection) {
        List<FileObject> files = new ArrayList<>();
        List<FileObject> openableFiles = new ArrayList<>();
        List<TreeItem<T>> deletable = new ArrayList<>();
        List<FileObject> deletableFiles = new ArrayList<>();
        for (TreeItem<T> item : selection) {
            FileObject fo = host.fileOf(item);
            if (fo == null) {
                continue;
            }
            files.add(fo);
            if (PackageScanner.isOpenable(fo)) {
                openableFiles.add(fo);
            }
            if (host.isDeletable(item)) {
                deletable.add(item);
                deletableFiles.add(fo);
            }
        }
        if (files.isEmpty()) {
            return null;
        }
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                openManyItem(openableFiles),
                new SeparatorMenuItem(),
                cutManyItem(deletableFiles),
                copyManyItem(deletableFiles),
                new SeparatorMenuItem(),
                deleteManyItem(deletable),
                new SeparatorMenuItem(),
                showInFileManagerManyItem(files));
        return menu;
    }

    private void addClipboardItems(ContextMenu menu, FileObject fo, boolean canCutCopy) {
        menu.getItems().addAll(
                cutItem(fo, canCutCopy),
                copyItem(fo, canCutCopy),
                pasteItem(fo));
    }

    /** Saves the modified documents of the project rooted at {@code root}, and of no other. */
    private static MenuItem saveItem(FileObject root) {
        MenuItem item = new MenuItem(message("ContextMenu.saveProject"));
        // The menu is built per click, so the state of the clicked project is read here rather
        // than bound to the command, whose enablement follows the selected project.
        item.setDisable(!hasModifiedDocuments(root));
        item.setOnAction(e -> {
            // Save Project acts on the selected project, so the clicked one is selected first.
            Projects.select(root);
            runCommand(ActionIds.SAVE_PROJECT);
        });
        return item;
    }

    /** Whether the project rooted at {@code root} has any document with unsaved changes. */
    private static boolean hasModifiedDocuments(FileObject root) {
        EditorContext context = Lookup.getDefault().lookup(EditorContext.class);
        if (context == null || root == null) {
            return false;
        }
        return context.documentsOf(OpenProject.pathOf(root)).stream()
                .anyMatch(document -> document.modifiedProperty().get());
    }

    /** Closes the project rooted at {@code root}: the clicked project, not just any open one. */
    private static MenuItem closeItem(FileObject root) {
        MenuItem item = new MenuItem(message("ContextMenu.close"));
        item.setOnAction(e -> {
            // Close Project acts on the selected project, so the clicked one is selected first.
            Projects.select(root);
            runCommand(ActionIds.CLOSE_PROJECT);
        });
        return item;
    }

    private static MenuItem closeAllItem() {
        MenuItem item = new MenuItem(message("ContextMenu.closeAll"));
        item.setOnAction(e -> runCommand(ActionIds.CLOSE_ALL_PROJECTS));
        return item;
    }

    private static void runCommand(String actionId) {
        ActionRegistry registry = Lookup.getDefault().lookup(ActionRegistry.class);
        if (registry != null) {
            registry.find(actionId).ifPresent(Command::run);
        }
    }

    private MenuItem openItem(TreeItem<T> item, FileObject fo) {
        MenuItem menuItem = new MenuItem(message("ContextMenu.open"));
        menuItem.setOnAction(e -> host.openFiles(List.of(fo)));
        return menuItem;
    }

    private MenuItem openManyItem(List<FileObject> files) {
        MenuItem item = new MenuItem(message("ContextMenu.open"));
        item.setDisable(files.isEmpty());
        item.setOnAction(e -> host.openFiles(files));
        return item;
    }

    private static MenuItem newItem() {
        MenuItem item = new MenuItem(message("ContextMenu.new"));
        item.setDisable(true);
        return item;
    }

    private static MenuItem cutItem(FileObject fo, boolean enabled) {
        MenuItem item = new MenuItem(message("ContextMenu.cut"));
        item.setAccelerator(shortcut(KeyCode.X));
        item.setDisable(!enabled);
        item.setOnAction(e -> FileClipboardActions.cut(List.of(fo)));
        return item;
    }

    private static MenuItem copyItem(FileObject fo, boolean enabled) {
        MenuItem item = new MenuItem(message("ContextMenu.copy"));
        item.setAccelerator(shortcut(KeyCode.C));
        item.setDisable(!enabled);
        item.setOnAction(e -> FileClipboardActions.copy(List.of(fo)));
        return item;
    }

    private static MenuItem pasteItem(FileObject fo) {
        MenuItem item = new MenuItem(message("ContextMenu.paste"));
        item.setAccelerator(shortcut(KeyCode.V));
        item.setDisable(!FileClipboardActions.canPaste());
        item.setOnAction(e -> FileClipboardActions.paste(pasteTarget(fo)));
        return item;
    }

    private static MenuItem cutManyItem(List<FileObject> files) {
        MenuItem item = new MenuItem(message("ContextMenu.cut"));
        item.setAccelerator(shortcut(KeyCode.X));
        item.setDisable(files.isEmpty());
        item.setOnAction(e -> FileClipboardActions.cut(files));
        return item;
    }

    private static MenuItem copyManyItem(List<FileObject> files) {
        MenuItem item = new MenuItem(message("ContextMenu.copy"));
        item.setAccelerator(shortcut(KeyCode.C));
        item.setDisable(files.isEmpty());
        item.setOnAction(e -> FileClipboardActions.copy(files));
        return item;
    }

    /** Resolves the folder a paste should target: the file object if a folder, else its parent. */
    private static FileObject pasteTarget(FileObject fo) {
        if (fo == null) {
            return null;
        }
        return fo.isFolder() ? fo : fo.getParent();
    }

    private MenuItem deleteItem(TreeItem<T> item) {
        MenuItem menuItem = new MenuItem(message("ContextMenu.delete"));
        menuItem.setAccelerator(new KeyCodeCombination(KeyCode.DELETE));
        menuItem.setOnAction(e -> host.deleteItems(List.of(item)));
        return menuItem;
    }

    private MenuItem deleteManyItem(List<TreeItem<T>> deletable) {
        MenuItem item = new MenuItem(message("ContextMenu.delete"));
        item.setAccelerator(new KeyCodeCombination(KeyCode.DELETE));
        item.setDisable(deletable.isEmpty());
        item.setOnAction(e -> host.deleteItems(deletable));
        return item;
    }

    private static MenuItem openInTerminalItem(FileObject fo) {
        MenuItem item = new MenuItem(message("ContextMenu.openInTerminal"));
        item.setOnAction(e -> runAfterHiding(item, () -> {
            File file = FileUtil.toFile(fo);
            if (file != null) {
                DesktopActions.openInTerminal(file);
            }
        }));
        return item;
    }

    private static MenuItem showInFileManagerItem(FileObject fo) {
        MenuItem item = new MenuItem(DesktopActions.revealLabel());
        item.setOnAction(e -> runAfterHiding(item, () -> {
            File file = FileUtil.toFile(fo);
            if (file != null) {
                DesktopActions.showInFileManager(file);
            }
        }));
        return item;
    }

    private static MenuItem showInFileManagerManyItem(List<FileObject> files) {
        MenuItem item = new MenuItem(DesktopActions.revealLabel());
        item.setOnAction(e -> runAfterHiding(item, () -> {
            for (FileObject fo : files) {
                File file = FileUtil.toFile(fo);
                if (file != null) {
                    DesktopActions.showInFileManager(file);
                }
            }
        }));
        return item;
    }

    /** Hides the menu item's owning context menu before running {@code action}. */
    private static void runAfterHiding(MenuItem item, Runnable action) {
        ContextMenu popup = item.getParentPopup();
        if (popup != null) {
            popup.hide();
        }
        Platform.runLater(action);
    }

    private static KeyCodeCombination shortcut(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN);
    }

    private static String message(String key) {
        return NbBundle.getMessage(NavigatorContextMenuFactory.class, key);
    }
}
