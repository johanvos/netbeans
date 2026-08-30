package com.gluonhq.netbeans.nbfx.navigator.utils;

import com.gluonhq.netbeans.nbfx.api.FileIconProvider;
import javafx.scene.Node;
import org.openide.filesystems.FileObject;
import org.openide.util.lookup.ServiceProvider;

/**
 * Publishes the navigator's file icons to the rest of the application, so an editor tab opened
 * without an explicit graphic still shows the same icon as the one opened from a navigator view.
 */
@ServiceProvider(service = FileIconProvider.class)
public class NavigatorFileIconProvider implements FileIconProvider {

    @Override
    public Node createIcon(FileObject file) {
        if (file == null) {
            return null;
        }
        String iconName = NavigatorIcons.getFileIconName(file);
        return iconName == null ? null : NavigatorIcons.createIconView(file, iconName);
    }
}
