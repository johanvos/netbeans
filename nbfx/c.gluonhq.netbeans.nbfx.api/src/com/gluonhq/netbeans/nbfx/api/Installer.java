package com.gluonhq.netbeans.nbfx.api;

import org.openide.modules.ModuleInstall;

/**
 *
 * @author johan
 */
public class Installer extends ModuleInstall {
    
    @Override
    public void restored() {
        System.out.println("JAVAFXAPI NBFX 0");
    }
}
