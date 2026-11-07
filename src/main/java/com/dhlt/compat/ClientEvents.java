package com.dhlt.compat;

import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Sprite-derived colors are cached per block state, so they must be dropped
 * whenever resource packs rebuild the atlases.
 */
@Mod.EventBusSubscriber(modid = DhLtCompatMod.MOD_ID, value = Side.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    @SubscribeEvent
    public static void onTextureStitch(TextureStitchEvent.Post event) {
        LittleTilesColor.clearCache();
    }
}
