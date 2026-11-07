package com.dhlt.compat;

import com.creativemd.littletiles.common.block.BlockTile;
import com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockColorOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkProcessingEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import net.minecraft.init.Blocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DH <-> LittleTiles compatibility layer.
 *
 * DH cannot resolve a color for LittleTiles blocks (no static model), so LODs
 * fall back to the oak-planks particle texture. This mod replaces every LT block
 * with a solid-color palette block state during DH chunk processing, using the
 * volume-weighted average color of the tiles inside the tile entity.
 *
 * Palette textures are plain white, so the color is delivered entirely through
 * DH's vertex color override, which makes the result independent of whether the
 * face texture path is active.
 */
@Mod(
        modid = DhLtCompatMod.MOD_ID,
        name = "DH LittleTiles Compat",
        version = "0.3.0",
        dependencies = "required-after:distanthorizons;required-after:littletiles"
)
public class DhLtCompatMod {

    public static final String MOD_ID = "dhltcompat";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    // counters are written from DH's chunk-processing worker threads
    private static final AtomicLong BLOCKS_SEEN = new AtomicLong();
    private static final AtomicLong OVERRIDES = new AtomicLong();
    private static final AtomicLong TE_MISSES = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicLong TILES_SUMMED = new AtomicLong();
    private static final AtomicLong PROCESS_NANOS = new AtomicLong();
    private static final AtomicLong INVISIBLE = new AtomicLong();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        CompatConfig.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        if (!CompatConfig.enabled) {
            LOGGER.info("dhltcompat: disabled by config, no DH hook registered.");
            return;
        }
        registerWrapperCreatedEvent();
        registerColorOverrideEvent();
        registerChunkProcessingEvent();
        LOGGER.info("dhltcompat: DH 3.3.3 LittleTiles LOD hook registered. "
                + "Note: LODs already on disk keep their old block data, clear the Distant Horizons "
                + "LOD database after installing or removing this mod.");
    }

    /** mark palette block states as eligible for the color override event */
    private void registerWrapperCreatedEvent() {
        DhApi.events.bind(DhApiBlockStateWrapperCreatedEvent.class, new DhApiBlockStateWrapperCreatedEvent() {
            @Override
            public void blockStateWrapperCreated(DhApiEventParam<DhApiBlockStateWrapperCreatedEvent.EventParam> param) {
                Object mcObject = param.value.getBlockStateWrapper().getWrappedMcObject();
                if (mcObject instanceof IBlockState && Palette.isPaletteState((IBlockState) mcObject)) {
                    param.value.setAllowApiColorOverride(true);
                }
            }
        });
    }

    /**
     * Applies the palette color to the LOD vertex color.
     *
     * Palette textures are plain white, so DH's own texture-derived color is white.
     * Writing the exact palette RGB here means the LOD color is correct regardless
     * of whether DH multiplies the face texture in or not
     * (white texture * palette vertex color = palette color).
     */
    private void registerColorOverrideEvent() {
        DhApi.events.bind(DhApiBlockColorOverrideEvent.class, new DhApiBlockColorOverrideEvent() {
            @Override
            public void onBlockColorOverridden(DhApiEventParam<DhApiBlockColorOverrideEvent.EventParam> param) {
                Object mcObject = param.value.getBlockStateWrapper().getWrappedMcObject();
                if (!(mcObject instanceof IBlockState)) {
                    return;
                }
                int index = Palette.getIndex((IBlockState) mcObject);
                if (index < 0) {
                    return;
                }
                int rgb = Palette.getRgb(index);
                // NOTE: DH's setColor signature is (alpha, red, green, blue),
                // not the RGBA order the name suggests.
                param.value.setColor(255, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            }
        });
    }

    /** replace LT blocks with the nearest palette state during DH chunk processing */
    private void registerChunkProcessingEvent() {
        DhApi.events.bind(DhApiChunkProcessingEvent.class, new DhApiChunkProcessingEvent() {
            @Override
            public void blockOrBiomeChangedDuringChunkProcessing(DhApiEventParam<DhApiChunkProcessingEvent.EventParam> param) {
                Object mcObject = param.value.currentBlock.getWrappedMcObject();
                if (!(mcObject instanceof IBlockState)) {
                    return;
                }
                IBlockState state = (IBlockState) mcObject;
                if (!(state.getBlock() instanceof BlockTile)) {
                    return;
                }

                long startNanos = System.nanoTime();
                long seen = BLOCKS_SEEN.incrementAndGet();

                World world;
                try {
                    world = (World) param.value.levelWrapper.getWrappedMcObject();
                } catch (Throwable t) {
                    applyFallback(param);
                    logStatsIfDue(seen);
                    return;
                }
                if (world == null) {
                    applyFallback(param);
                    logStatsIfDue(seen);
                    return;
                }

                int x = (param.value.chunkX << 4) + param.value.relativeBlockPosX;
                int y = param.value.blockPosY;
                int z = (param.value.chunkZ << 4) + param.value.relativeBlockPosZ;
                BlockPos pos = new BlockPos(x, y, z);

                TileEntity te;
                try {
                    te = world.getTileEntity(pos);
                } catch (Throwable t) {
                    te = null;
                }
                if (!(te instanceof TileEntityLittleTiles)) {
                    TE_MISSES.incrementAndGet();
                    applyFallback(param);
                    logStatsIfDue(seen);
                    return;
                }

                LittleTilesColor.Result result;
                try {
                    result = LittleTilesColor.averageColor((TileEntityLittleTiles) te, world, pos);
                } catch (Throwable t) {
                    // a concurrent tile edit can abort iteration; fall back rather than skip the block
                    FALLBACKS.incrementAndGet();
                    applyFallback(param);
                    logStatsIfDue(seen);
                    return;
                }

                TILES_SUMMED.addAndGet(result.tileCount);

                // DH derives LOD transparency from the block state, so a mostly-transparent
                // tile entity has to become air, otherwise it renders as a solid cube.
                if (CompatConfig.transparencyEnabled
                        && result.opaqueFraction < CompatConfig.transparencyThreshold) {
                    INVISIBLE.incrementAndGet();
                    applyStateOverride(param, Blocks.AIR.getDefaultState());
                    PROCESS_NANOS.addAndGet(System.nanoTime() - startNanos);
                    logStatsIfDue(seen);
                    return;
                }

                int rgb = result.rgb;
                if (rgb < 0) {
                    FALLBACKS.incrementAndGet();
                    rgb = CompatConfig.fallbackRgb();
                } else if (CompatConfig.glowBoostFactor > 1.0D && result.emissiveFraction >= 0.5D) {
                    rgb = brighten(rgb, CompatConfig.glowBoostFactor);
                }
                applyOverride(param, rgb);

                PROCESS_NANOS.addAndGet(System.nanoTime() - startNanos);
                logStatsIfDue(seen);
            }
        });
    }

    /** scales a 0xRRGGBB color towards white, used as a cheap glow approximation */
    private static int brighten(int rgb, double factor) {
        int r = Math.min(255, (int) Math.round(((rgb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) Math.round(((rgb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) Math.round((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    /** override with the configured fallback color instead of leaving the oak-planks placeholder */
    private static void applyFallback(DhApiEventParam<DhApiChunkProcessingEvent.EventParam> param) {
        FALLBACKS.incrementAndGet();
        applyOverride(param, CompatConfig.fallbackRgb());
    }

    private static void applyOverride(DhApiEventParam<DhApiChunkProcessingEvent.EventParam> param, int rgb) {
        applyStateOverride(param, Palette.getState(Palette.quantize(rgb)));
    }

    private static void applyStateOverride(DhApiEventParam<DhApiChunkProcessingEvent.EventParam> param, IBlockState state) {
        try {
            param.value.setBlockOverride(BlockStateWrapper.fromBlockState(
                    state, (ILevelWrapper) param.value.levelWrapper));
            OVERRIDES.incrementAndGet();
        } catch (Throwable t) {
            LOGGER.warn("dhltcompat: failed to apply palette override at {}/{}/{}",
                    param.value.chunkX, param.value.blockPosY, param.value.chunkZ, t);
        }
    }

    private static void logStatsIfDue(long seen) {
        int interval = CompatConfig.statsLogEveryBlocks;
        if (interval <= 0 || seen % interval != 0) {
            return;
        }
        long overrides = OVERRIDES.get();
        long tiles = TILES_SUMMED.get();
        long nanos = PROCESS_NANOS.get();
        LOGGER.info(String.format(
                "dhltcompat stats: %d LT blocks, %d overrides (%d invisible), %d TE missing, "
                        + "%d fallbacks, avg %.1f tiles/block, avg %.0f us/block",
                seen, overrides, INVISIBLE.get(), TE_MISSES.get(), FALLBACKS.get(),
                (double) tiles / Math.max(1, overrides),
                (double) nanos / 1000.0 / Math.max(1, seen)));
    }
}
