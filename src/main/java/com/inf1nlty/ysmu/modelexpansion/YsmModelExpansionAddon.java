package com.inf1nlty.ysmu.modelexpansion;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fox.ysmu.model.ServerModelManager;

import net.fabricmc.api.ModInitializer;

public final class YsmModelExpansionAddon implements ModInitializer {

    public static final String MOD_ID = "ysmu_model_expansion";
    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        try {
            ModelExpansionInstaller.InstallResult result = ModelExpansionInstaller.install();
            LOG.info(
                "Prepared {} YSM expansion model(s) with {} resource file(s) ({} changed)",
                result.modelCount(),
                result.fileCount(),
                result.changedFileCount());
            if (result.changed()) ServerModelManager.reloadPacks();
        } catch (Exception exception) {
            LOG.error("Unable to install YSM expansion models", exception);
        }
    }
}
