package me.natron.mixin.celeritas;

import java.util.concurrent.ConcurrentLinkedDeque;

import me.natron.render.NatronUploadBudget;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Caps how many finished section meshes a single {@code uploadChunks} call uploads.
 * <p>
 * {@code collectChunkBuildResults} drains its result queue with an unbounded loop:
 *
 * <pre>
 *   while ((result = this.buildResults.poll()) != null) { ... }
 * </pre>
 *
 * so everything the workers finished since the last frame is uploaded in one frame. See
 * {@link NatronUploadBudget} for why that is fine for Angelica and not here.
 * <p>
 * Redirecting the {@code poll} rather than overwriting the method keeps the loop, its metrics call
 * and its failure handling exactly as Celeritas wrote them - this only decides when {@code poll}
 * starts answering {@code null}. Whatever is left stays queued for the next frame, which is safe
 * because {@code filterChunkBuildResults} already drops results whose section was disposed or
 * rebuilt since. It has to: workers finish asynchronously, so a result can be stale by the time the
 * render thread reaches it whether or not anything here deferred it.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinRenderSectionManagerUploadBudget {

    /**
     * Results handed out during the drain in progress; zero between drains.
     * <p>
     * "Between drains" is inferred from this being zero rather than tracked explicitly, which
     * leaves one way to desync: Celeritas's drain loop ends with a {@code throw new AssertionError()}
     * for a result that is neither a success nor a failure, and an exception leaving the loop that
     * way would strand a non-zero count here. That is self-correcting rather than sticky - the next
     * drain reads a stale count already at or past its cap, returns {@code null} immediately, and
     * resets on the way out, so the cost is one frame that uploads nothing. Not worth a second
     * injector to pre-empt, given the branch it depends on is unreachable unless
     * {@code ChunkJobResult} grows a third subtype.
     */
    @Unique
    private int natron$drained;

    /** Ceiling for the drain in progress, computed once when it starts. */
    @Unique
    private int natron$drainCap;

    /**
     * Set once teardown begins, after which the cap is lifted for good.
     * <p>
     * {@code destroy} is the other caller of {@code collectChunkBuildResults}, and unlike
     * {@code uploadChunks} it needs the drain to be total:
     *
     * <pre>
     *   this.builder.shutdown();
     *   for (var result : this.collectChunkBuildResults()) {
     *       result.output().delete();
     *   }
     * </pre>
     *
     * That loop is what releases the off-heap buffers held by tasks still pending or just
     * cancelled. Capping it would strand them with nothing left alive to drain the queue, leaking
     * native memory on every world change.
     */
    @Unique
    private boolean natron$tearingDown;

    @Inject(method = "destroy", at = @At("HEAD"))
    private void natron$liftCapForTeardown(CallbackInfo ci) {
        this.natron$tearingDown = true;
    }

    @Redirect(
        method = "collectChunkBuildResults",
        at = @At(value = "INVOKE",
                 target = "Ljava/util/concurrent/ConcurrentLinkedDeque;poll()Ljava/lang/Object;"))
    private Object natron$boundedPoll(ConcurrentLinkedDeque<?> buildResults) {
        if (this.natron$tearingDown) {
            return buildResults.poll();
        }

        if (this.natron$drained == 0) {
            // size() walks the whole deque on ConcurrentLinkedDeque, so it is read once when a
            // drain begins rather than on every poll.
            this.natron$drainCap = NatronUploadBudget.capFor(buildResults.size());
        }

        if (this.natron$drained >= this.natron$drainCap) {
            this.natron$drained = 0;
            return null;
        }

        final Object result = buildResults.poll();

        if (result == null) {
            this.natron$drained = 0;
            return null;
        }

        this.natron$drained++;

        return result;
    }
}
