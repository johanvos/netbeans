package com.gluonhq.netbeans.nbfx.launcher;

import com.gluonhq.netbeans.nbfx.api.ContentManager;
import com.gluonhq.netbeans.nbfx.api.EditorDocument;

import java.util.ArrayList;
import java.util.List;

import org.openide.filesystems.FileObject;
import org.openide.util.Lookup;

/**
 * Shared Close Document / Close All / Close Other Documents behavior, used by both the main window's
 * Window menu (scoped to the main pane's selected editor) and each detached window's Window menu
 * (scoped to that window's selected editor). Every close confirms any unsaved changes first, so a
 * save never fails silently and the user cannot lose data unknowingly.
 */
final class DocumentCloser {

    private DocumentCloser() {
    }

    /** Closes {@code document} (in any pane), confirming any unsaved changes first. */
    static void closeDocument(EditorDocument document) {
        if (document == null) {
            return;
        }
        ContentManager cm = contentManager();
        FileObject file = document.getFileObject();
        if (cm == null || file == null) {
            return;
        }
        if (!CloseConfirmation.confirmClose(List.of(document))) {
            return;
        }
        cm.closeFile(file);
    }

    /** Closes all open editor documents (across all windows), confirming any unsaved changes first. */
    static void closeAllDocuments() {
        ContentManager cm = contentManager();
        if (cm == null) {
            return;
        }
        List<EditorDocument> documents = documents();
        if (documents.isEmpty() || !CloseConfirmation.confirmClose(documents)) {
            return;
        }
        cm.closeAll();
    }

    /** Closes every open editor document except {@code keep}, confirming any unsaved changes first. */
    static void closeOtherDocuments(EditorDocument keep) {
        if (keep == null) {
            return;
        }
        ContentManager cm = contentManager();
        if (cm == null) {
            return;
        }
        List<EditorDocument> others = new ArrayList<>();
        for (EditorDocument document : documents()) {
            if (document != keep) {
                others.add(document);
            }
        }
        if (others.isEmpty() || !CloseConfirmation.confirmClose(others)) {
            return;
        }
        others.forEach(document -> {
            FileObject file = document.getFileObject();
            if (file != null) {
                cm.closeFile(file);
            }
        });
    }

    private static List<EditorDocument> documents() {
        return EditorContexts.documentsSnapshot();
    }

    private static ContentManager contentManager() {
        return Lookup.getDefault().lookup(ContentManager.class);
    }
}
