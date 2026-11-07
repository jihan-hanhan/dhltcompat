package com.dhlt.compat;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 224-color palette used as DH LOD proxy blocks.
 * 14 blocks x 16 metadata states = 224 colors:
 * indices 0..215 are a 6x6x6 RGB cube (levels 0,51,...,255),
 * indices 216..223 are 8 grayscale steps.
 *
 * The palette blocks exist only as blockstate carriers for DH's LOD
 * serialization; they are never placed in the world.
 */
@Mod.EventBusSubscriber(modid = DhLtCompatMod.MOD_ID)
public final class Palette {

    public static final int BLOCK_COUNT = 14;
    public static final int META_COUNT = 16;
    public static final int COLOR_COUNT = BLOCK_COUNT * META_COUNT; // 224
    public static final PropertyInteger VARIANT = PropertyInteger.create("variant", 0, META_COUNT - 1);

    /** packed sRGB (0xRRGGBB) for every palette index */
    private static final int[] RGB_BY_INDEX = new int[COLOR_COUNT];
    private static final BlockPalette[] BLOCKS = new BlockPalette[BLOCK_COUNT];
    private static final IBlockState[] STATES = new IBlockState[COLOR_COUNT];

    static {
        int index = 0;
        // 6x6x6 color cube
        int[] levels = {0, 51, 102, 153, 204, 255};
        for (int r : levels) {
            for (int g : levels) {
                for (int b : levels) {
                    RGB_BY_INDEX[index++] = (r << 16) | (g << 8) | b;
                }
            }
        }
        // 8 grayscale steps
        int[] grays = {8, 44, 80, 116, 148, 184, 220, 248};
        for (int gray : grays) {
            RGB_BY_INDEX[index++] = (gray << 16) | (gray << 8) | gray;
        }
    }

    private Palette() {}

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        for (int i = 0; i < BLOCK_COUNT; i++) {
            BlockPalette block = new BlockPalette();
            block.setRegistryName(DhLtCompatMod.MOD_ID, "palette_" + i);
            block.setPaletteBlockIndex(i);
            BLOCKS[i] = block;
            event.getRegistry().register(block);
            for (int meta = 0; meta < META_COUNT; meta++) {
                STATES[i * META_COUNT + meta] = block.getDefaultState().withProperty(VARIANT, meta);
            }
        }
    }

    public static IBlockState getState(int index) {
        return STATES[index];
    }

    public static int getRgb(int index) {
        return RGB_BY_INDEX[index];
    }

    public static boolean isPaletteState(IBlockState state) {
        return state != null && state.getBlock() instanceof BlockPalette;
    }

    /**
     * Palette index of the given state, or -1 if it isn't a palette state.
     * Derived from the registration order, so no extra map is needed.
     */
    public static int getIndex(IBlockState state) {
        if (!isPaletteState(state)) {
            return -1;
        }
        int blockIndex = ((BlockPalette) state.getBlock()).getPaletteBlockIndex();
        return blockIndex * META_COUNT + state.getValue(VARIANT);
    }

    /** nearest palette index for a packed 0xRRGGBB color (simple RGB euclidean distance) */
    public static int quantize(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < COLOR_COUNT; i++) {
            int pr = (RGB_BY_INDEX[i] >> 16) & 0xFF;
            int pg = (RGB_BY_INDEX[i] >> 8) & 0xFF;
            int pb = RGB_BY_INDEX[i] & 0xFF;
            int dr = r - pr;
            int dg = g - pg;
            int db = b - pb;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    public static final class BlockPalette extends Block {
        private int paletteBlockIndex = -1;

        public BlockPalette() {
            super(Material.ROCK);
            setDefaultState(blockState.getBaseState().withProperty(VARIANT, 0));
        }

        void setPaletteBlockIndex(int index) {
            this.paletteBlockIndex = index;
        }

        public int getPaletteBlockIndex() {
            return this.paletteBlockIndex;
        }

        @Override
        protected BlockStateContainer createBlockState() {
            return new BlockStateContainer(this, VARIANT);
        }

        @Override
        public IBlockState getStateFromMeta(int meta) {
            return getDefaultState().withProperty(VARIANT, meta);
        }

        @Override
        public int getMetaFromState(IBlockState state) {
            return state.getValue(VARIANT);
        }
    }
}
