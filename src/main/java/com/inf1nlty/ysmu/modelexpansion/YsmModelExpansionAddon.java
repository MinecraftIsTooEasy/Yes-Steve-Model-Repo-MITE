package com.inf1nlty.ysmu.modelexpansion;

import java.lang.reflect.Method;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.api.ModInitializer;

public final class YsmModelExpansionAddon implements ModInitializer {

    public static final String MOD_ID = "ysmu_model_expansion";
    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        try {
            ModelExpansionInstaller.InstallResult result = ModelExpansionInstaller.install();
            LOG.info(
                "Installed {} YSM expansion model(s) with {} resource file(s)",
                result.modelCount(),
                result.fileCount());
            reloadYsmModels();
        } catch (Exception exception) {
            LOG.error("Unable to install YSM expansion models", exception);
        }
    }

    private static void reloadYsmModels() throws ReflectiveOperationException {
        Class<?> manager = Class.forName("com.fox.ysmu.model.ServerModelManager");
        Method reloadPacks = manager.getMethod("reloadPacks");
        reloadPacks.invoke(null);
    }
}
