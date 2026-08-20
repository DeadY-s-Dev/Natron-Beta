package me.natron.mixin.celeritas;

import java.nio.ByteBuffer;

import me.natron.render.NatronGlCaps;
import org.embeddedt.embeddium.impl.gl.arena.staging.FallbackStagingBuffer;
import org.embeddedt.embeddium.impl.gl.buffer.GlBuffer;
import org.embeddedt.embeddium.impl.gl.buffer.GlBufferTarget;
import org.embeddedt.embeddium.impl.gl.device.CommandList;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the staging hop for mesh uploads on drivers that cannot copy buffer-to-buffer on the GPU.
 * <p>
 * Which staging buffer Celeritas picks is decided by the driver, not by us:
 * {@code MappedStagingBuffer} needs OpenGL 4.4 or {@code ARB_buffer_storage}, and everything else
 * falls back to {@code FallbackStagingBuffer}. That fallback uploads the section's vertices into a
 * scratch GL buffer and then asks for a GPU-side copy into the region arena:
 *
 * <pre>
 *   commandList.uploadData(fallbackBufferObject, data, STREAM_COPY);
 *   commandList.copyBufferSubData(fallbackBufferObject, dst, 0, writeOffset, data.remaining());
 * </pre>
 *
 * {@code GlBufferArena.tryUpload} calls this once <em>per section</em>, so on a driver that also
 * lacks {@code glCopyBufferSubData} the emulation in {@link MixinImmediateCommandList} would run
 * per section too - and that emulation contains a {@code glGetBufferSubData}, a synchronous
 * read back that stalls the pipeline. Paying a stall for every section built is what turns a
 * chunk-loading burst into a visible freeze.
 * <p>
 * The round trip is avoidable rather than merely expensive. The data being staged is already in
 * system memory: the only reason it goes to a scratch buffer first is so the driver can move it
 * GPU-side. With no GPU-side copy available there is nothing to gain from the detour, so this
 * writes the vertices straight into the arena buffer with a single {@code glBufferSubData} and
 * cancels the original. One upload instead of an upload, a stalling read back, and another upload.
 * <p>
 * Drivers that do have the native copy are left completely alone - the injection returns without
 * cancelling and the stock path runs.
 */
@Mixin(value = FallbackStagingBuffer.class, remap = false)
public abstract class MixinFallbackStagingBuffer {

    @Inject(method = "enqueueCopy", at = @At("HEAD"), cancellable = true)
    private void natron$writeStraightToArena(CommandList commandList, ByteBuffer data,
                                                GlBuffer dst, long writeOffset, CallbackInfo ci) {
        if (NatronGlCaps.hasNativeBufferCopy()) {
            // The stock two-step path stays on the GPU and is the faster one here.
            return;
        }

        // Bind through the command list rather than raw GL: Celeritas caches which buffer is bound
        // per target, and binding behind its back would make it skip a later bind that matters.
        commandList.bindBuffer(GlBufferTarget.ARRAY_BUFFER, dst);

        // LWJGL uploads position..limit. The caller reads data.remaining() after this returns in
        // the stock path, so the position is restored rather than left wherever GL leaves it.
        final int position = data.position();
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, writeOffset, data);
        data.position(position);

        ci.cancel();
    }
}
