package com.gluonhq.netbeans.nbfx.navigator;

import java.util.List;
import java.util.function.Consumer;

import org.openide.filesystems.FileObject;

/**
 *
 * @author johan
 */
public class ProjectTreeCell extends NavigatorTreeCell<ProjectEntry> {

    public ProjectTreeCell(Consumer<List<FileObject>> reselectMoved) {
        super(reselectMoved, NavigatorHosts.project());
    }

    @Override
    protected void updateItem(ProjectEntry item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setGraphic(null);
            setText(null);
        } else if (getTreeItem() instanceof ProjectTreeItem treeItem) {
            setGraphic(treeItem.getGraphic());
            setText(item.getName());
        } else {
            // shouldn't happen, but just in case
            setGraphic(null);
            setText(item.getName());
        }
    }

}
