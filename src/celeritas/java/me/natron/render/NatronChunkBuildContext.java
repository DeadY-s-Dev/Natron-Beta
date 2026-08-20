package me.natron.render;

import net.minecraft.client.renderer.RegionRenderCacheBuilder;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.util.EnumWorldBlockLayer;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;

/**
 * Port of Angelica's {@code AngelicaChunkBuildContext}: per-worker scratch state that Celeritas
 * itself knows nothing about.
 * <p>
 * Angelica has to carry a whole tessellator emulation here because 1.7.10 block rendering writes
 * into a global {@code Tessellator}. 1.8.9 already hands geometry to a per-call
 * {@link WorldRenderer}, and vanilla already ships a holder for one buffer per block layer, so
 * this just borrows {@link RegionRenderCacheBuilder}.
 */
public class NatronChunkBuildContext extends ChunkBuildContext {

    // Deliberately not named "buffers": ChunkBuildContext already has a field by that name
    // holding Celeritas's own mesh builders, and shadowing it silently breaks every
    // context.buffers call in the meshing task.
    private final RegionRenderCacheBuilder vanillaBuffers;

    /**
     * Scratch a section build needs but does not need a fresh copy of.
     * <p>
     * Celeritas hands each worker thread its own context and a worker builds one section at a time,
     * so everything here is exclusive to that worker for the length of a build - the same property
     * that already lets {@link #vanillaBuffers} be shared this way. These were allocated inside the
     * build itself before, once per section, which during world load is thousands of short-lived
     * objects a second across all workers.
     * <p>
     * Deliberately limited to objects that are fully reset by the build using them. The transcoder
     * qualifies because everything it carries is per-quad scratch overwritten before it is read,
     * <em>provided</em> {@link VanillaBufferTranscoder#refreshLayout()} runs first - that is what
     * keeps a reused instance from decoding with a vertex format that has since changed.
     * {@code BuiltRenderSectionData} and the mesh map do not qualify: they are handed to Celeritas
     * as part of the finished output and outlive the build that produced them.
     */
    private final VanillaBufferTranscoder transcoder = new VanillaBufferTranscoder();

    private final boolean[] layerStarted = new boolean[EnumWorldBlockLayer.values().length];
    private final boolean[] layerHasGeometry = new boolean[EnumWorldBlockLayer.values().length];

    public NatronChunkBuildContext(RenderPassConfiguration<?> configuration) {
        super(configuration);
        this.vanillaBuffers = new RegionRenderCacheBuilder();
    }

    public WorldRenderer getBuffer(EnumWorldBlockLayer layer) {
        return this.vanillaBuffers.getWorldRendererByLayer(layer);
    }

    /** @return this worker's transcoder, with its vertex layout brought up to date */
    public VanillaBufferTranscoder beginSection() {
        this.transcoder.refreshLayout();
        return this.transcoder;
    }

    /** @return this worker's layer flags, cleared for a new section */
    public boolean[] resetLayerStarted() {
        java.util.Arrays.fill(this.layerStarted, false);
        return this.layerStarted;
    }

    /** @return this worker's layer flags, cleared for a new section */
    public boolean[] resetLayerHasGeometry() {
        java.util.Arrays.fill(this.layerHasGeometry, false);
        return this.layerHasGeometry;
    }
}
