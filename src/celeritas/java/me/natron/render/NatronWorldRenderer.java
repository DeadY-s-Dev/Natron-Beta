package me.natron.render;

import java.nio.FloatBuffer;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumWorldBlockLayer;
import org.embeddedt.embeddium.impl.gl.device.CommandList;
import org.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import org.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import org.lwjgl.opengl.GL11;

/**
 * Port of Angelica's {@code CeleritasWorldRenderer} - the object that owns the renderer for the
 * current world and drives it once per frame.
 * <p>
 * Angelica's version is 621 lines, but most of that is Iris shadow passes, dynamic lights and
 * entity culling. {@link SimpleWorldRenderer} already implements the frame flow (chunk events,
 * async task pumping, terrain setup, layer draw), so what is version-specific here is small:
 * where to get the camera matrices, how far to render, and how tall the world is.
 */
public class NatronWorldRenderer
        extends SimpleWorldRenderer<WorldClient, NatronRenderSectionManager, EnumWorldBlockLayer, TileEntity, Object> {

    private static NatronWorldRenderer instance;

    /** 1.8.9 is a fixed 0..255 world; there is no Cubic Chunks to ask. */
    private static final int MIN_BUILD_HEIGHT = 0;
    private static final int MAX_BUILD_HEIGHT = 256;

    private final FloatBuffer projectionBuffer = GLAllocation.createDirectFloatBuffer(16);
    private final FloatBuffer modelViewBuffer = GLAllocation.createDirectFloatBuffer(16);

    public static NatronWorldRenderer getInstance() {
        if (instance == null) {
            instance = new NatronWorldRenderer();
        }
        return instance;
    }

    public static NatronWorldRenderer getInstanceOrNull() {
        return instance;
    }

    @Override
    public int getEffectiveRenderDistance() {
        return Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
    }

    @Override
    public int getMinimumBuildHeight() {
        return MIN_BUILD_HEIGHT;
    }

    @Override
    public int getMaximumBuildHeight() {
        return MAX_BUILD_HEIGHT;
    }

    /**
     * Built fresh per call, from buffers {@link #refreshCameraMatrices()} filled once this frame.
     * <p>
     * The buffers are the expensive half and they are already shared - what is left here is one
     * record and two {@code Matrix4f} parses per layer draw. Caching that record for the whole
     * frame was tried and reverted: it measured worse, alongside entities failing to render, so
     * whatever it interacted with was not what the reasoning behind it assumed. Four small
     * allocations a frame is not worth a second attempt without a profile pointing here first.
     */
    @Override
    protected ChunkRenderMatrices createChunkRenderMatrices() {
        this.projectionBuffer.rewind();
        this.modelViewBuffer.rewind();

        return new ChunkRenderMatrices(this.projectionBuffer, this.modelViewBuffer);
    }

    /**
     * The one GL matrix read for this frame. Called from the {@code setupTerrain} hook, before
     * either the frustum or any layer's {@link ChunkRenderMatrices} are built, so both reuse it.
     * <p>
     * Reading {@code glGetFloatv(GL_PROJECTION_MATRIX)}/{@code GL_MODELVIEW_MATRIX} per layer used
     * to cost ten driver round trips a frame; on drivers where that call synchronizes with the GPU,
     * that alone was enough to drop this to single-digit FPS. The camera does not move between
     * {@code setupTerrain} and the layer draws that follow it in the same frame, so one read - and
     * one {@link ChunkRenderMatrices} built from it - is enough.
     */
    public void refreshCameraMatrices() {
        this.projectionBuffer.clear();
        this.modelViewBuffer.clear();

        GlStateManager.getFloat(GL11.GL_PROJECTION_MATRIX, this.projectionBuffer);
        GlStateManager.getFloat(GL11.GL_MODELVIEW_MATRIX, this.modelViewBuffer);

        this.projectionBuffer.rewind();
        this.modelViewBuffer.rewind();
    }

    public FloatBuffer getProjectionBuffer() {
        return this.projectionBuffer;
    }

    public FloatBuffer getModelViewBuffer() {
        return this.modelViewBuffer;
    }

    @Override
    protected NatronRenderSectionManager createRenderSectionManager(CommandList commandList) {
        final int renderDistance = getEffectiveRenderDistance();

        return new NatronRenderSectionManager(
            NatronRenderPassConfiguration.build(
                org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats.COMPACT),
            this.world,
            renderDistance,
            commandList,
            MIN_BUILD_HEIGHT >> 4,
            MAX_BUILD_HEIGHT >> 4,
            // 0 means "you decide" - Celeritas then calls its own getOptimalThreadCount(), which
            // is what Angelica ships too (its chunkBuilderThreads option defaults to 0).
            NatronRenderSectionManager.AUTO_THREAD_COUNT);
    }

    /**
     * Tile entity rendering still runs through vanilla's own pass for now; Celeritas only needs to
     * know which ones are in visible sections once that is taken over.
     */
    @Override
    protected void renderBlockEntityList(List<TileEntity> list, Object context) {
        // intentionally empty until block entity rendering is moved over
    }

    /**
     * Schedules rebuilds for a changed area.
     * <p>
     * This used to drop cached section clones covering the area first, back when a
     * {@code ClonedChunkSectionCache} sat between the world and the mesher. Nothing caches the world
     * between changes now - each build snapshots it fresh on its worker - so there is nothing left
     * to invalidate and the rebuild alone is the whole job.
     *
     * @param x1, y1, z1, x2, y2, z2 inclusive block bounds of the change
     */
    public void invalidateAndScheduleRebuild(int x1, int y1, int z1, int x2, int y2, int z2) {
        scheduleRebuildForBlockArea(x1, y1, z1, x2, y2, z2, false);
    }
}
