package com.gluonhq.netbeans.nbfx.navigator;

import java.util.logging.Logger;
import org.openide.modules.ModuleInstall;

public class Installer extends ModuleInstall {

    private static final Logger LOG = Logger.getLogger(Installer.class.getName());

    @Override
    public void restored() {
        LOG.info("nbfx-filenavigator has been restored.");
    }
}

