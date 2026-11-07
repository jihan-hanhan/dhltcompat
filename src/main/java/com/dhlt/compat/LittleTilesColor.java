package com.dhlt.compat;

import com.creativemd.creativecore.common.utils.type.Pair;
import com.creativemd.littletiles.common.tile.LittleTile;
import com.creativemd.littletiles.common.tile.LittleTileColored;
import com.creativemd.littletiles.common.tile.parent.IParentTileList;
import com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles;
import com.seibel.distanthorizons.common.wrappers.block.ClientBlockStateColorCache;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes the representative color of a LittleTiles tile entity.
 *
 * Colors come from:
 * 1. {@link LittleTileColored#color} for explicitly colored tiles,
 * 2. otherwise the inner block state's texture average, calculated with DH's
 *    own {@link ClientBlockStateColorCache#calculateColorFromTexture} so that
 *    patterned blocks (lapis, ores, planks...) match how DH handles vanilla blocks.
 *
 * The result also reports how much of the volume is transparent, because DH
 * decides LOD transparency from the block state rather than from color alpha.
 */
public final class LittleTilesColor {

    private static final ConcurrentHashMap<IBlockState, TileColor> TILE_COLOR_CACHE = new ConcurrentHashMap<>();
    private static final Object MODEL_LOCK = new Object();
    private static final int FALLBACK_GRAY = 0x808080;

    private LittleTilesColor() {}

    /** color info for one inner block state */
    private static final class TileColor {
        final int rgb;
        final boolean transparent;
        final boolean emissive;

        TileColor(int rgb, boolean transparent, boolean emissive) {
            this.rgb = rgb;
            this.transparent = transparent;
            this.emissive = emissive;
        }
    }

    /** aggregated result of one tile-entity scan */
    public static final class Result {
        /** volume-weighted 0xRRGGBB over the opaque tiles, or -1 if none had a color */
        public final int rgb;
        /** tiles that contributed to the average */
        public final int tileCount;
        /** opaque volume / total volume, 0..1 */
        public final double opaqueFraction;
        /** emissive volume / opaque volume, 0..1 */
        public final double emissiveFraction;

        public Result(int rgb, int tileCount, double opaqueFraction, double emissiveFraction) {
            this.rgb = rgb;
            this.tileCount = tileCount;
            this.opaqueFraction = opaqueFraction;
            this.emissiveFraction = emissiveFraction;
        }
    }

    public static Result averageColor(TileEntityLittleTiles te, World world, BlockPos pos) {
        double totalVolume = 0.0;
        double opaqueVolume = 0.0;
        double emissiveVolume = 0.0;
        double r = 0.0;
        double g = 0.0;
        double b = 0.0;
        int tileCount = 0;

        for (Pair<IParentTileList, LittleTile> pair : te.allTiles()) {
            LittleTile tile = pair.value;
            if (tile == null) {
                continue;
            }
            double volume = tile.getVolume();
            if (volume <= 0.0) {
                continue;
            }
            tileCount++;
            totalVolume += volume;

            TileColor color;
            try {
                color = colorOf(tile, world, pos);
            } catch (Throwable t) {
                continue; // skip misbehaving tiles instead of failing the whole block
            }

            if (color.transparent) {
                continue;
            }
            opaqueVolume += volume;
            if (color.emissive) {
                emissiveVolume += volume;
            }
            r += ((color.rgb >> 16) & 0xFF) * volume;
            g += ((color.rgb >> 8) & 0xFF) * volume;
            b += (color.rgb & 0xFF) * volume;
        }

        if (totalVolume <= 0.0) {
            return new Result(-1, tileCount, 0.0, 0.0);
        }

        double opaqueFraction = opaqueVolume / totalVolume;
        if (opaqueVolume <= 0.0) {
            return new Result(-1, tileCount, 0.0, 0.0);
        }
        int ri = (int) Math.round(r / opaqueVolume);
        int gi = (int) Math.round(g / opaqueVolume);
        int bi = (int) Math.round(b / opaqueVolume);
        double emissiveFraction = emissiveVolume / opaqueVolume;
        return new Result((ri << 16) | (gi << 8) | bi, tileCount, opaqueFraction, emissiveFraction);
    }

    private static TileColor colorOf(LittleTile tile, World world, BlockPos pos) {
        if (tile instanceof LittleTileColored) {
            // explicit LT colors are RGB-packed; alpha bits (if any) are masked off
            return new TileColor(((LittleTileColored) tile).color & 0xFFFFFF, false, false);
        }
        IBlockState inner = tile.getBlockState();
        if (inner == null) {
            return new TileColor(FALLBACK_GRAY, false, false);
        }
        TileColor cached = TILE_COLOR_CACHE.get(inner);
        if (cached != null) {
            return cached;
        }
        TileColor computed = computeTileColor(inner, world, pos);
        TILE_COLOR_CACHE.putIfAbsent(inner, computed);
        return computed;
    }

    private static TileColor computeTileColor(IBlockState state, World world, BlockPos pos) {
        boolean emissive = isEmissive(state);
        synchronized (MODEL_LOCK) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                TextureAtlasSprite sprite = mc.getBlockRendererDispatcher()
                        .getBlockModelShapes().getTexture(state);
                if (sprite != null) {
                    int argb = ClientBlockStateColorCache.calculateColorFromTexture(
                            sprite, ClientBlockStateColorCache.EColorMode.Default);
                    int alpha = (argb >>> 24) & 0xFF;
                    if (alpha <= 0) {
                        // texture is entirely transparent (glass, pane, etc.)
                        return new TileColor(0, true, emissive);
                    }
                    return new TileColor(argb & 0xFFFFFF, false, emissive);
                }
            } catch (Throwable ignored) {
                // client classes unavailable or the model failed; fall through
            }
        }

        // fallback: flat map color (coarse, but better than nothing)
        int rgb;
        try {
            rgb = state.getMapColor(world, pos).colorValue & 0xFFFFFF;
        } catch (Throwable t) {
            rgb = FALLBACK_GRAY;
        }
        return new TileColor(rgb, false, emissive);
    }

    private static boolean isEmissive(IBlockState state) {
        try {
            return state.getBlock().getLightValue(state) > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** must be called on resource reload so stale sprite-derived colors aren't reused */
    public static void clearCache() {
        TILE_COLOR_CACHE.clear();
    }
}
