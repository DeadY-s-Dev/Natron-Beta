package me.natron.render;

import java.nio.ByteBuffer;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RegionRenderCache;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.chunk.SetVisibility;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumWorldBlockLayer;
import net.minecraftforge.client.ForgeHooksClient;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import org.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import org.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import org.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import org.embeddedt.embeddium.impl.render.chunk.occlusion.VisibilityEncoding;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import org.embeddedt.embeddium.impl.util.task.CancellationToken;
import org.lwjgl.opengl.GL11;

/**
 * 1.8.9 counterpart of Angelica's {@code AngelicaChunkBuilderMeshingTask}.
 * <p>
 * Angelica has to drive 1.7.10's {@code RenderBlocks}/ISBRH by hand, because on that version every
 * block's geometry is hardcoded in Java and there is no shared way to ask for it. 1.8.9 already
 * funnels every block through {@link BlockRendererDispatcher} into a {@link WorldRenderer}, so this
 * lets vanilla emit the geometry - ambient occlusion, smooth lighting, biome tint and any Forge
 * model extensions included - and then moves the finished buffer into Celeritas's mesh builders.
 * <p>
 * Everything past this point is still Celeritas: region-batched buffer arenas, the occlusion graph,
 * multidraw and the async scheduler. Only the source of the triangles is vanilla.
 * <p>
 * The whole section is rendered into one buffer per block layer rather than one per block, so
 * vanilla's per-call overhead is paid 4 times per section instead of 16384 times.
 * <p>
 * The world snapshot is a {@link RegionRenderCache} built here, inside {@link #execute} - meaning
 * on a worker thread, which is also where vanilla's own {@code ChunkRenderWorker.processTask}
 * builds the identical structure.
 * <p>
 * <b>This was moved to the render thread once, and moved back.</b> Angelica and Sodium prepare
 * their snapshot on the render thread, out of a cache of cloned chunk sections shared between
 * neighbouring builds, and this port followed them there: a {@code WorldSlice} over a
 * {@code ClonedChunkSectionCache}, on the render thread, in {@code createRebuildTask}. Measured
 * against the build that came before it, that cost roughly a third of the frame rate.
 * <p>
 * The reason it works for them and not here is what the two meshers do afterwards. Angelica's
 * writes geometry into Celeritas's vertex format directly, so its worker threads finish quickly and
 * the render thread is the scarce resource worth optimising. This one drives vanilla's block
 * renderer into a vanilla buffer and then transcodes that whole buffer into Celeritas's format -
 * far more work per section, all of it on workers, of which there are several. Workers are the
 * throughput bottleneck here and the render thread is the frame rate. Taking the snapshot off six
 * worker threads and putting it on the one render thread traded the resource there is a surplus of
 * for the one there is not.
 */
public class NatronMeshingTask extends ChunkBuilderTask<ChunkBuildOutput> {

    private static final EnumWorldBlockLayer[] LAYERS = EnumWorldBlockLayer.values();
    private static final EnumFacing[] FACINGS = EnumFacing.values();
    private static final int SECTION_SIZE = 16;

    /**
     * One block of padding, matching vanilla. {@code RegionRenderCache} indexes a fixed array sized
     * for exactly this, so the bounds handed to its constructor have to be origin-1 to origin+16.
     */
    private static final int SNAPSHOT_PADDING = 1;

    private final RenderSection render;
    private final WorldClient world;
    private final BlockPos origin;
    private final int sectionIndex;
    private final float camX;
    private final float camY;
    private final float camZ;

    public NatronMeshingTask(RenderSection render, WorldClient world, BlockPos origin,
                                int sectionIndex, float camX, float camY, float camZ) {
        this.render = render;
        this.world = world;
        this.origin = origin;
        this.sectionIndex = sectionIndex;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
    }

    @Override
    public ChunkBuildOutput execute(ChunkBuildContext context, CancellationToken cancellationToken) {
        if (!(context instanceof NatronChunkBuildContext)) {
            throw new IllegalStateException(
                "expected NatronChunkBuildContext, got " + context.getClass().getName());
        }

        final long startTime = System.nanoTime();
        final NatronChunkBuildContext ctx = (NatronChunkBuildContext) context;
        final BuiltRenderSectionData renderData = new BuiltRenderSectionData();
        final VisGraph visGraph = new VisGraph();
        // Per worker, not per section. beginSection() refreshes its vertex layout first.
        final VanillaBufferTranscoder transcoder = ctx.beginSection();

        // Built here rather than passed in: this runs on a worker, and the copy is the expensive
        // part. See the class comment for why that placement is deliberate.
        final BlockPos from = this.origin.add(-SNAPSHOT_PADDING, -SNAPSHOT_PADDING, -SNAPSHOT_PADDING);
        final BlockPos to = this.origin.add(SECTION_SIZE, SECTION_SIZE, SECTION_SIZE);
        final RegionRenderCache snapshot =
            new RegionRenderCache(this.world, from, to, SNAPSHOT_PADDING);

        context.buffers.init(renderData, this.sectionIndex);

        final BlockRendererDispatcher dispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        final int originX = this.origin.getX();
        final int originY = this.origin.getY();
        final int originZ = this.origin.getZ();

        final boolean[] layerStarted = ctx.resetLayerStarted();
        final boolean[] layerHasGeometry = ctx.resetLayerHasGeometry();


        // One pass over the section's 4096 blocks, with the layer loop on the inside - the same
        // shape as vanilla's RenderChunk.rebuildChunk. An earlier version had the layer loop on
        // the outside, which meant walking the whole volume four times and paying 16384
        // getBlockState lookups per section instead of 4096. That is four times the meshing cost
        // on every rebuild, which is invisible in a settled world but very much not during the
        // initial load, when every visible section is being built at once.
        for (int y = 0; y < SECTION_SIZE; y++) {
            // Per slice rather than per block: responsive enough to a cancelled build without
            // putting a branch in the innermost loop.
            if (cancellationToken.isCancelled()) {
                abortStartedBuffers(ctx, layerStarted);
                return null;
            }

            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    pos.set(originX + x, originY + y, originZ + z);

                    final IBlockState state = snapshot.getBlockState(pos);
                    final Block block = state.getBlock();

                    // Air first, by reference, before anything virtual is called on it. Most of a
                    // typical section is air, and for air every check below is a foregone
                    // conclusion: it is not an opaque cube, and its render type is -1. Angelica's
                    // own mesher opens its block loop with exactly this comparison for the same
                    // reason - one reference compare replaces two virtual calls, on the majority
                    // of the 4096 positions this loop visits.
                    if (block == Blocks.air) {
                        continue;
                    }

                    if (block.isOpaqueCube()) {
                        visGraph.func_178606_a(pos);
                    }
                    if (block.getRenderType() == -1) {
                        continue;
                    }

                    for (int i = 0; i < LAYERS.length; i++) {
                        final EnumWorldBlockLayer layer = LAYERS[i];

                        if (!block.canRenderInLayer(layer)) {
                            continue;
                        }

                        // Modded blocks read this back via MinecraftForgeClient.getRenderLayer()
                        // to decide what geometry to emit for the layer being built. Vanilla sets
                        // it here too; it is a ThreadLocal, so each worker carries its own.
                        //
                        // Set unconditionally, even though this is a ThreadLocal.set and the value
                        // is usually the one already there. Tracking the last layer to skip the
                        // redundant sets was tried and dropped: renderBlock below runs mod code,
                        // which is free to set the layer itself, and a tracker that missed such a
                        // write would silently feed every later block the wrong layer. Vanilla
                        // makes no such assumption either. The saving would have been around a
                        // percent of what renderBlock itself costs - not worth a silent failure.
                        ForgeHooksClient.setRenderLayer(layer);

                        final WorldRenderer buffer = ctx.getBuffer(layer);

                        // Started lazily, like vanilla's isLayerStarted check: a section with no
                        // translucent blocks never touches the translucent buffer at all.
                        if (!layerStarted[i]) {
                            layerStarted[i] = true;
                            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                            // Celeritas encodes positions relative to the section, so vanilla is
                            // asked to emit section-local coordinates rather than world ones.
                            buffer.setTranslation(-originX, -originY, -originZ);
                        }

                        layerHasGeometry[i] |= dispatcher.renderBlock(state, pos, snapshot, buffer);
                    }
                }
            }
        }

        boolean anyGeometry = false;

        for (int i = 0; i < LAYERS.length; i++) {
            if (!layerStarted[i]) {
                continue;
            }

            final WorldRenderer buffer = ctx.getBuffer(LAYERS[i]);
            buffer.setTranslation(0.0D, 0.0D, 0.0D);
            buffer.finishDrawing();

            if (layerHasGeometry[i] && buffer.getVertexCount() > 0) {
                anyGeometry = true;
                transcodeLayer(ctx, transcoder, buffer, LAYERS[i]);
            }

            buffer.reset();
        }

        renderData.hasBlockGeometry = anyGeometry;
        renderData.visibilityData = encodeVisibility(visGraph);

        final Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes =
            new Reference2ReferenceOpenHashMap<TerrainRenderPass, BuiltSectionMeshParts>();

        for (TerrainRenderPass pass : context.buffers.getBuilderPasses()) {
            final BuiltSectionMeshParts mesh =
                context.buffers.createMesh(pass, this.camX, this.camY, this.camZ);
            if (mesh != null) {
                meshes.put(pass, mesh);
            }
        }

        final int buildTime = (int) ((System.nanoTime() - startTime) / 1000L);
        return new ChunkBuildOutput(this.render, renderData, meshes, buildTime);
    }

    /**
     * Closes out any buffer this task had begun before it was cancelled.
     * <p>
     * {@code WorldRenderer.begin} throws {@code IllegalStateException("Already building!")} if the
     * buffer is still drawing, and these buffers live on the worker's shared build context - so
     * bailing out without finishing them would leave a live grenade for whatever task this worker
     * picks up next.
     */
    private static void abortStartedBuffers(NatronChunkBuildContext ctx, boolean[] layerStarted) {
        for (int i = 0; i < LAYERS.length; i++) {
            if (!layerStarted[i]) {
                continue;
            }

            final WorldRenderer buffer = ctx.getBuffer(LAYERS[i]);
            buffer.setTranslation(0.0D, 0.0D, 0.0D);
            buffer.finishDrawing();
            buffer.reset();
        }
    }

    private void transcodeLayer(NatronChunkBuildContext ctx, VanillaBufferTranscoder transcoder,
                                WorldRenderer buffer, EnumWorldBlockLayer layer) {
        final Material material = materialFor(layer);
        final ByteBuffer bytes = buffer.getByteBuffer();
        bytes.position(0);
        transcoder.transcode(bytes, buffer.getVertexCount(), context(ctx, material), material);
    }

    private org.embeddedt.embeddium.impl.render.chunk.compile.buffers.ChunkModelBuilder context(
            NatronChunkBuildContext ctx, Material material) {
        return ctx.buffers.get(material);
    }

    private static Material materialFor(EnumWorldBlockLayer layer) {
        switch (layer) {
            case SOLID:
                return NatronRenderPassConfiguration.SOLID_MATERIAL;
            case CUTOUT:
                return NatronRenderPassConfiguration.CUTOUT_MATERIAL;
            case CUTOUT_MIPPED:
                return NatronRenderPassConfiguration.CUTOUT_MIPPED_MATERIAL;
            case TRANSLUCENT:
            default:
                return NatronRenderPassConfiguration.TRANSLUCENT_MATERIAL;
        }
    }

    /**
     * Celeritas's {@code GraphDirection} constants are DOWN, UP, NORTH, SOUTH, WEST, EAST - the
     * same order as {@link EnumFacing}'s ordinals - so the two index spaces line up directly.
     */
    private static long encodeVisibility(VisGraph visGraph) {
        final SetVisibility visibility = visGraph.computeVisibility();

        return VisibilityEncoding.encode(new VisibilityEncoding.DataHolder() {
            @Override
            public boolean canFaceSeeFace(int fromDir, int toDir) {
                return visibility.isVisible(FACINGS[fromDir], FACINGS[toDir]);
            }
        });
    }
}
