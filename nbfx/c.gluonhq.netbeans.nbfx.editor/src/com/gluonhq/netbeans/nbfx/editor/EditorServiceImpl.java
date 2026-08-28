package com.gluonhq.netbeans.nbfx.editor;

import com.gluonhq.netbeans.nbfx.api.EditorDocument;
import com.gluonhq.netbeans.nbfx.api.EditorService;
import com.gluonhq.netbeans.nbfx.editor.codearea.CodeEditor;

import java.util.logging.Logger;

import org.openide.filesystems.FileObject;
import org.openide.util.lookup.ServiceProvider;

/**
 * NetBeans service that creates a {@link CodeEditor} document for a given file.
 */
@ServiceProvider(service = EditorService.class)
public class EditorServiceImpl implements EditorService {

    static final Logger LOG = Logger.getLogger(EditorServiceImpl.class.getName());

    @Override
    public EditorDocument createDocument(FileObject fo) {
        LOG.info("Will create an editor for " + fo);
        return new CodeEditor(fo);
    }
}
