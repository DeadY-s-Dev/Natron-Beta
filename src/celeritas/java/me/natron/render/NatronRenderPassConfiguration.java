package me.natron.render;

import java.util.EnumMap;
import java.util.Map;

import com.google.common.collect.ImmutableListMultimap;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumWorldBlockLayer;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.lwjgl.opengl.GL11;

/**
 * Port of Angelica's {@code AngelicaRenderPassConfiguration} onto 1.8.9's block layers.
 * <p>
 * 1.7.10 has no notion of render layers on the block itself, so Angelica introduces its own
 * {@code BlockRenderLayer}. 1.8.9 already splits terrain into {@link EnumWorldBlockLayer}, so the
 * mapping is direct. The pass/material split is kept exactly as Angelica has it: cutout and
 * cutout-mipped share one pass and differ only in alpha cutoff and mipping.
 */
public final class NatronRenderPassConfiguration {

    public static TerrainRenderPass SOLID_PASS;
    public static TerrainRenderPass CUTOUT_MIPPED_PASS;
    public static TerrainRenderPass TRANSLUCENT_PASS;

    public static Material SOLID_MATERIAL;
    public static Material CUTOUT_MATERIAL;
    public static Material CUTOUT_MIPPED_MATERIAL;
    public static Material TRANSLUCENT_MATERIAL;

    private NatronRenderPassConfiguration() {
    }

    /**
     * The alpha-test func/ref that was true before terrain rendering touched anything, valid for
     * the whole frame - {@code -1} means "not read yet this frame".
     * <p>
     * {@code SOLID_PASS} and {@code CUTOUT_MIPPED_PASS} both use pipeline slot 0, and
     * {@code CUTOUT_MIPPED_PASS} in particular gets drawn twice a frame (vanilla routes both the
     * CUTOUT and CUTOUT_MIPPED layers to it - see the layer map built in {@link #build}). Without
     * this cache, {@code NatronPipelineState.setup()} queried {@code GL_ALPHA_TEST_FUNC}/
     * {@code GL_ALPHA_TEST_REF} on every one of those calls: up to three synchronous glGet round
     * trips a frame for a value that cannot have changed since the last read - the same class of
     * bug the per-layer camera matrix reads turned out to be in {@link NatronWorldRenderer}.
     */
    private static int cachedAlphaFunc = -1;
    private static float cachedAlphaRef;

    /** Reads the baseline alpha-test state once. Call at most once per frame, before any pass draws. */
    public static void refreshAlphaBaseline() {
        cachedAlphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        cachedAlphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
    }

    private static TerrainRenderPass.TerrainRenderPassBuilder builderFor(
            int pass, boolean disableAlphaTest, ChunkVertexType vertexType) {
        return TerrainRenderPass.builder()
            .pipelineState(new NatronPipelineState(pass, disableAlphaTest))
            .vertexType(vertexType)
            .primitiveType(QuadPrimitiveType.TRIANGULATED);
    }

    public static RenderPassConfiguration<EnumWorldBlockLayer> build(ChunkVertexType vertexType) {
        SOLID_PASS = builderFor(0, true, vertexType)
            .name("solid")
            .fragmentDiscard(false)
            .useReverseOrder(false)
            .build();

        CUTOUT_MIPPED_PASS = builderFor(0, false, vertexType)
            .name("cutout_mipped")
            .fragmentDiscard(true)
            .useReverseOrder(false)
            .build();

        TRANSLUCENT_PASS = builderFor(1, false, vertexType)
            .name("translucent")
            .fragmentDiscard(false)
            .useReverseOrder(true)
            .useTranslucencySorting(true)
            .build();

        SOLID_MATERIAL = new Material(SOLID_PASS, AlphaCutoffParameter.ZERO, true);
        CUTOUT_MIPPED_MATERIAL = new Material(CUTOUT_MIPPED_PASS, AlphaCutoffParameter.HALF, true);
        CUTOUT_MATERIAL = new Material(CUTOUT_MIPPED_PASS, AlphaCutoffParameter.ONE_TENTH, false);
        TRANSLUCENT_MATERIAL = new Material(TRANSLUCENT_PASS, AlphaCutoffParameter.ZERO, true);

        final ImmutableListMultimap.Builder<EnumWorldBlockLayer, TerrainRenderPass> stages =
            ImmutableListMultimap.builder();
        stages.put(EnumWorldBlockLayer.SOLID, SOLID_PASS);
        stages.put(EnumWorldBlockLayer.CUTOUT, CUTOUT_MIPPED_PASS);
        stages.put(EnumWorldBlockLayer.CUTOUT_MIPPED, CUTOUT_MIPPED_PASS);
        stages.put(EnumWorldBlockLayer.TRANSLUCENT, TRANSLUCENT_PASS);

        final Map<EnumWorldBlockLayer, Material> materials =
            new EnumMap<EnumWorldBlockLayer, Material>(EnumWorldBlockLayer.class);
        materials.put(EnumWorldBlockLayer.SOLID, SOLID_MATERIAL);
        materials.put(EnumWorldBlockLayer.CUTOUT, CUTOUT_MATERIAL);
        materials.put(EnumWorldBlockLayer.CUTOUT_MIPPED, CUTOUT_MIPPED_MATERIAL);
        materials.put(EnumWorldBlockLayer.TRANSLUCENT, TRANSLUCENT_MATERIAL);

        return new RenderPassConfiguration<EnumWorldBlockLayer>(
            materials, stages.build().asMap(),
            CUTOUT_MIPPED_MATERIAL, CUTOUT_MIPPED_MATERIAL, TRANSLUCENT_MATERIAL);
    }

    /**
     * Angelica saves and restores the alpha function through its own state manager. 1.8.9's
     * GlStateManager keeps the same state but does not expose a getter, so the previous values
     * come from {@link #refreshAlphaBaseline}'s once-a-frame read instead of querying GL here.
     */
    private static final class NatronPipelineState implements TerrainRenderPass.PipelineState {

        private final int pass;
        private final boolean disableAlphaTest;

        private NatronPipelineState(int pass, boolean disableAlphaTest) {
            this.pass = pass;
            this.disableAlphaTest = disableAlphaTest;
        }

        @Override
        public void setup() {
            GlStateManager.depthMask(true);

            if (this.pass == 0) {
                GlStateManager.alphaFunc(GL11.GL_GREATER, AlphaCutoffParameter.HALF.cutoff());
            }
            if (this.disableAlphaTest) {
                GlStateManager.disableAlpha();
            }
        }

        @Override
        public void clear() {
            if (this.pass == 0) {
                GlStateManager.alphaFunc(cachedAlphaFunc, cachedAlphaRef);
            }
            if (this.disableAlphaTest) {
                GlStateManager.enableAlpha();
            }
        }
    }
}
