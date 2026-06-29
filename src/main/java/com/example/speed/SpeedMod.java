package com.example.speed;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpeedMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("speedmod");

    @Override
    public void onInitialize() {
        LOGGER.info("Speed Mod is running!");
    }
}
