package me.natron.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.embeddedt.embeddium.impl.gl.device.CommandList;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import org.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import org.embeddedt.embeddium.impl.render.chunk.occlusion.AsyncOcclusionMode;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.jetbrains.annotations.Nullable;

/**
 * Port of Angelica's {@code AngelicaRenderSectionManager}.
 * <p>
 * This is where the version-specific half plugs into Celeritas: the manager owns the occlusion
 * graph, the region arenas and the async build queue, and asks this subclass four questions plus
 * "give me a task to rebuild this section".
 * <p>
 * Angelica's version is 365 lines because it also carries Iris shadow passes, Cubic Chunks support
 * and its own section cache. None of that applies here.
 */
public class NatronRenderSectionManager extends RenderSectionManager {

    private final WorldClient world;


    private float cameraX;
    private float cameraY;
    private float cameraZ;

    public NatronRenderSectionManager(RenderPassConfiguration<?> configuration, WorldClient world,
                                         int renderDistance, CommandList commandList,
                                         int minSection, int maxSection, int requestedThreads) {
        super(configuration,
            new ContextSupplier(configuration),
            NatronChunkRenderer::new,
            renderDistance, commandList, minSection, maxSection, requestedThreads,
            // hasShadowPass: the shorter constructor that omits this is deprecated. Shadow passes
            // only exist for Iris, which is not part of this port.
            false);
        this.world = world;
    }


    /**
     * Celeritas calls this once per worker thread, so each worker gets its own vanilla buffers.
     * Written out rather than as a lambda because the source set targets Java 8 and this keeps the
     * captured configuration explicit.
     */
    private static final class ContextSupplier
            implements java.util.function.Supplier<org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext> {

        private final RenderPassConfiguration<?> configuration;

        ContextSupplier(RenderPassConfiguration<?> configuration) {
            this.configuration = configuration;
        }

        @Override
        public org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext get() {
            return new NatronChunkBuildContext(this.configuration);
        }
    }

    /**
     * Whether the section the camera sits in has been registered yet.
     * <p>
     * Tracked here rather than asked of {@code RenderSectionManager}, whose {@code sectionByPosition}
     * map is private - Angelica reaches it through an accessor mixin; {@link #onSectionAdded} is
     * public and gives the same answer for the one section this needs to know about.
     */
    private boolean initialCameraSectionReady;

    /** Packed coordinates of every section registered so far, for {@link #initialCameraSectionReady}. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet knownSections =
        new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /**
     * Marks sections belonging to chunks that have not arrived yet as fully occluding.
     * <p>
     * Ported from Angelica's override of the same method. Celeritas builds its occlusion graph by
     * walking outward from the camera through whatever each section reports as see-through, and a
     * section that has never been built reports nothing blocking - so the walk pours straight into
     * the region the server has not sent yet and keeps going. Handing those sections a visibility
     * mask of zero stops the walk at the edge of what actually exists, which is most of the world
     * during the first seconds after joining.
     */
    @Override
    public void onSectionAdded(int x, int y, int z) {
        super.onSectionAdded(x, y, z);

        // Only while it is still being consulted. Recording every section for the whole session
        // would be a slow leak of one long per section the player ever goes near, for a set that
        // answers exactly one question and is then finished with.
        if (!this.initialCameraSectionReady) {
            this.knownSections.add(packSection(x, y, z));
        }

        // Angelica's own test, which is Chunk.isEmpty() rather than a class check. Not
        // chunkExists(): ChunkProviderClient overrides that to return true unconditionally on
        // 1.8.9, so asking it would mark nothing and quietly disable this method. What the client
        // hands back for a coordinate it has not received is a shared EmptyChunk, whose isEmpty()
        // is true where a real chunk's is false.
        //
        // Worth being plain about how often this fires here: sections are added from the tracker's
        // forEachReady/forEachEvent, and this port feeds that tracker from doPreChunk - so by the
        // time a section is added, its chunk has normally just arrived and this test is false. What
        // it still covers is the gap where a chunk was marked ready and then unloaded before the
        // frame that processes the event. Kept because Angelica keeps it and it costs one call per
        // section added, not because it is doing the heavy lifting below.
        if (!this.world.getChunkFromChunkCoords(x, z).isEmpty()) {
            return;
        }

        this.renderListManager.updateVisibilityData(x, y, z, 0L);
    }

    /**
     * Skips the graph walk entirely until the camera's own section exists.
     * <p>
     * Also ported from Angelica. The walk starts at the camera and follows visibility outward, so
     * before that section is registered there is nothing to start from and the whole traversal is
     * wasted work - repeated every frame, during the exact window where the render thread has the
     * least to spare. Once it appears the flag latches and this never checks again.
     */
    @Override
    public void update(Viewport viewport, int frame, boolean spectator) {
        if (!this.initialCameraSectionReady) {
            final org.joml.Vector3ic camera = viewport.getChunkCoord();

            if (!this.knownSections.contains(packSection(camera.x(), camera.y(), camera.z()))) {
                return;
            }

            this.initialCameraSectionReady = true;

            // Done with it. clear() alone leaves the backing table at whatever size the load burst
            // grew it to, so trim() is what actually returns the memory.
            this.knownSections.clear();
            this.knownSections.trim();
        }

        super.update(viewport, frame, spectator);
    }

    /** Same packing Celeritas uses for section keys. */
    private static long packSection(int x, int y, int z) {
        return org.embeddedt.embeddium.impl.util.PositionUtil.packSection(x, y, z);
    }

    /** Records where the camera is, so rebuild tasks can sort translucent geometry against it. */
    public void setCameraPosition(double x, double y, double z) {
        this.cameraX = (float) x;
        this.cameraY = (float) y;
        this.cameraZ = (float) z;
    }

    /**
     * Angelica's default, and for the same reason it is a default there: this is not about shadows.
     * <p>
     * This returned {@code NONE} on the reasoning that "shadow passes only exist with Iris, which is
     * not ported, so there is nothing to run occlusion for asynchronously". That reading of the enum
     * was wrong. {@code RenderSectionManager}'s constructor uses it twice, and only the second use
     * is about shadows:
     *
     * <pre>
     *   renderListManager       = new RenderListManager(.., getAsyncOcclusionMode() == EVERYTHING, ..)
     *   shadowRenderListManager = new RenderListManager(.., getAsyncOcclusionMode() != NONE, ..)
     * </pre>
     *
     * The first is the <em>main</em> render list, and {@code EVERYTHING} is what makes its occlusion
     * graph walk run off the render thread. Returning {@code NONE} left that walk running
     * synchronously, every frame, on the render thread - work Angelica does not do there.
     * <p>
     * Nothing this subclass supplies gets less safe under it: {@code shouldUseOcclusionCulling} is
     * the one thing the async graph walk calls back into here, and Angelica's own override reads the
     * live world in exactly the same way on exactly the same path. {@code isSectionVisuallyEmpty},
     * which also reads the world, is only reached from {@code onSectionAdded} and
     * {@code scheduleRebuildAll} - both render-thread work, neither part of the graph walk. The
     * shadow list stays unbuilt regardless, since this manager is constructed with
     * {@code hasShadowPass = false}.
     */
    @Override
    protected AsyncOcclusionMode getAsyncOcclusionMode() {
        return AsyncOcclusionMode.EVERYTHING;
    }

    @Override
    protected boolean useFogOcclusion() {
        return true;
    }

    @Override
    protected boolean shouldUseOcclusionCulling(Viewport viewport, boolean spectator) {
        if (spectator) {
            // Vanilla does the same check: inside an opaque block there is no visible face to
            // start the graph walk from, so culling would hide everything.
            final BlockPos pos = new BlockPos(
                viewport.getBlockCoord().x(), viewport.getBlockCoord().y(), viewport.getBlockCoord().z());
            if (this.world.getBlockState(pos).getBlock().isOpaqueCube()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isSectionVisuallyEmpty(int x, int y, int z) {
        final Chunk chunk = this.world.getChunkFromChunkCoords(x, z);

        if (chunk == null || chunk.isEmpty()) {
            return true;
        }

        final ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();

        if (y < 0 || y >= sections.length) {
            return true;
        }

        final ExtendedBlockStorage section = sections[y];
        return section == null || section.isEmpty();
    }

    /**
     * Computes only the section origin. The world snapshot itself is built inside
     * {@link NatronMeshingTask#execute}, on a worker thread - see that class for why it belongs
     * there rather than here, and what it cost when it did not.
     */
    @Override
    protected @Nullable ChunkBuilderTask<ChunkBuildOutput> createRebuildTask(RenderSection render, int frame) {
        final int x = render.getChunkX();
        final int y = render.getChunkY();
        final int z = render.getChunkZ();

        if (isSectionVisuallyEmpty(x, y, z)) {
            return null;
        }

        final BlockPos origin = new BlockPos(x << 4, y << 4, z << 4);

        return new NatronMeshingTask(render, this.world, origin, render.getSectionIndex(),
            this.cameraX, this.cameraY, this.cameraZ);
    }


    /**
     * Hands the worker count decision to Celeritas rather than making it here.
     * <p>
     * This used to compute {@code max(1, min(cores - 1, 6))}, which is far too many on the core
     * counts most 1.8.9 players have. Celeritas's own {@code getOptimalThreadCount} is
     * {@code clamp(max(cores / 3, cores - 6), 1, 10)}, and it caps that against available heap as
     * well ({@code maxMemory / 64MB}), which the old formula ignored entirely:
     *
     * <pre>
     *   cores    old    Celeritas
     *       4      3            1
     *       6      5            2
     *       8      6            2
     *      16      6           10
     * </pre>
     *
     * On an eight-core machine that is six worker threads competing with the render thread instead
     * of two. Workers are what build sections; the render thread is what draws frames, and this
     * port has already measured twice over that render-thread contention is what costs frame rate.
     * Oversubscribing the CPU to build chunks faster pays for it in exactly the number the user
     * sees. Celeritas is also more generous than the old formula above twelve cores, where there is
     * room to be.
     * <p>
     * {@code ChunkBuilder} treats a requested count of zero as "choose for me" - the same value
     * Angelica's {@code chunkBuilderThreads} option defaults to.
     */
    public static final int AUTO_THREAD_COUNT = 0;

    public static WorldClient currentWorld() {
        return Minecraft.getMinecraft().theWorld;
    }
}
