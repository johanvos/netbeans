package com.gluonhq.netbeans.nbfx.navigator;

import java.util.List;
import java.util.function.Consumer;

import org.openide.filesystems.FileObject;

public class FileTreeCell extends NavigatorTreeCell<FileObject> {

    public FileTreeCell(Consumer<List<FileObject>> reselectMoved) {
        super(reselectMoved, NavigatorHosts.files());
    }

    @Override
    protected void updateItem(FileObject item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            setText(null);
        } else if (getTreeItem() instanceof FileTreeItem treeItem) {
            setGraphic(treeItem.getGraphic());
            setText(item.getNameExt());
        } else {
            // shouldn't happen, but just in case
            setGraphic(null);
            setText(item.getName());
        }
    }

}
