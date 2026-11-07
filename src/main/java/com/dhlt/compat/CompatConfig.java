package com.dhlt.compat;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Runtime configuration (config/dhltcompat.cfg).
 * Loaded during preInit, before any hook is registered.
 */
public final class CompatConfig {

    public static final int DEFAULT_FALLBACK_RGB = 0x808080;

    /** master switch; when false no DH hook is registered at all */
    public static boolean enabled = true;

    /** color used when a LittleTiles block's tile entity is missing or yields no tiles */
    public static String fallbackColor = "808080";

    /** log one aggregated stats line every N processed LittleTiles blocks; 0 disables it */
    public static int statsLogEveryBlocks = 1000;

    /** replace mostly-transparent blocks with air so DH doesn't render them as solid */
    public static boolean transparencyEnabled = true;

    /** blocks with an opaque volume fraction below this become invisible */
    public static double transparencyThreshold = 0.2D;

    /** brightness multiplier applied to emissive-dominant blocks; 1.0 disables the effect */
    public static double glowBoostFactor = 1.0D;

    private static int fallbackRgbCache = DEFAULT_FALLBACK_RGB;

    private CompatConfig() {}

    public static void load(File configFile) {
        Configuration config = new Configuration(configFile);
        try {
            config.load();

            enabled = config.getBoolean(
                    "enabled", "general", true,
                    "Register the DH <-> LittleTiles LOD compatibility hook.");

            fallbackColor = config.getString(
                    "fallbackColor", "general", "808080",
                    "RRGGBB hex used when a LittleTiles tile entity is missing or empty.");

            statsLogEveryBlocks = config.getInt(
                    "statsLogEveryBlocks", "general", 1000, 0, Integer.MAX_VALUE,
                    "Log aggregated statistics every N processed blocks. 0 disables logging.");

            transparencyEnabled = config.getBoolean(
                    "transparencyEnabled", "general", true,
                    "Turn mostly-transparent LittleTiles blocks into air in the LOD. "
                            + "DH decides LOD transparency from the block state, not from color alpha.");

            transparencyThreshold = config.getFloat(
                    "transparencyThreshold", "general", 0.2F, 0.0F, 1.0F,
                    "Blocks whose opaque volume fraction is below this become invisible.");

            glowBoostFactor = config.getFloat(
                    "glowBoostFactor", "general", 1.0F, 1.0F, 8.0F,
                    "Brightness multiplier for emissive-dominant blocks. 1.0 disables it "
                            + "(DH LOD has no real light emission).");
        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }

        fallbackRgbCache = parseHex(fallbackColor);
    }

    public static int fallbackRgb() {
        return fallbackRgbCache;
    }

    private static int parseHex(String value) {
        if (value == null) {
            return DEFAULT_FALLBACK_RGB;
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        }
        try {
            int parsed = Integer.parseInt(cleaned, 16);
            if (cleaned.length() <= 6) {
                return parsed & 0xFFFFFF;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the default
        }
        return DEFAULT_FALLBACK_RGB;
    }
}
