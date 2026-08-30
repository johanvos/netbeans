package com.gluonhq.netbeans.nbfx.file.actions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A reversible paste of one or more entries into a target folder.
 *
 * <p>Each entry is captured as a {@code source → destination} pair computed when the paste was
 * performed. For a copy, undo deletes the created destination and redo re-copies from the source;
 * for a move (from a cut), undo moves the destination back to the source and redo moves it forward
 * again.</p>
 */
final class PasteEdit implements FileEdit {

    /** A single pasted entry: where it came from and where it landed. */
    record Entry(Path source, Path destination) {
    }

    private final List<Entry> entries;
    private final boolean move;

    PasteEdit(List<Entry> entries, boolean move) {
        this.entries = List.copyOf(entries);
        this.move = move;
    }

    @Override
    public void undo() throws IOException {
        List<Path> refresh = new ArrayList<>();
        for (Entry entry : entries) {
            if (move) {
                FileOps.move(entry.destination(), entry.source());
                refresh.add(entry.source());
            } else {
                FileOps.deleteRecursively(entry.destination());
            }
            refresh.add(entry.destination());
        }
        FileOps.refreshParents(refresh.toArray(Path[]::new));
    }

    @Override
    public void redo() throws IOException {
        List<Path> refresh = new ArrayList<>();
        for (Entry entry : entries) {
            if (move) {
                FileOps.move(entry.source(), entry.destination());
                refresh.add(entry.source());
            } else {
                FileOps.copyRecursively(entry.source(), entry.destination());
            }
            refresh.add(entry.destination());
        }
        FileOps.refreshParents(refresh.toArray(Path[]::new));
    }
}
