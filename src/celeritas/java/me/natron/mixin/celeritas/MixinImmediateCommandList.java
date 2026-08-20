package me.natron.mixin.celeritas;

import java.nio.ByteBuffer;

import com.mitchej123.lwjgl.LWJGLServiceProvider;
import me.natron.render.NatronGlCaps;
import org.embeddedt.embeddium.impl.gl.buffer.GlBuffer;
import org.embeddedt.embeddium.impl.gl.buffer.GlBufferTarget;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Emulates {@code glCopyBufferSubData} for drivers that do not have it.
 * <p>
 * macOS only ever hands out a legacy OpenGL 2.1 context to a game that uses fixed-function
 * rendering, and this machine's capability dump reads:
 *
 * <pre>
 *   GL_APPLE_vertex_array_object      = true
 *   GL_ARB_draw_elements_base_vertex  = true
 *   GL_ARB_copy_buffer                = false   &lt;- the only gap
 * </pre>
 *
 * So vertex arrays and the base-vertex batched draw path both work, and buffer-to-buffer copying
 * is the single thing standing between Celeritas and running here. Without the native call the
 * copy has to go through system memory with {@code glGetBufferSubData} + {@code glBufferSubData},
 * both OpenGL 1.5.
 * <p>
 * <b>That round trip is not cheap.</b> An earlier version of this comment claimed it was "only
 * reached when a region arena grows or compacts, not per frame". That was wrong, and worth
 * spelling out because the mistake hid a real stall: {@code GlBufferArena.tryUpload} calls
 * {@code stagingBuffer.enqueueCopy} once per section, and {@code FallbackStagingBuffer} implements
 * that with a {@code copyBufferSubData}. On a driver missing both {@code ARB_buffer_storage} and
 * {@code ARB_copy_buffer}, every single section built would land here - and
 * {@code glGetBufferSubData} is a synchronous read back that drains the pipeline.
 * {@link MixinFallbackStagingBuffer} now diverts that path so it never reaches this method.
 * <p>
 * What is left is the genuinely occasional caller: {@code GlBufferArena.transferSegments}, run
 * when an arena resizes or compacts. Those come in bursts of one copy per live segment, so the
 * staging memory is reused across calls instead of allocating per copy.
 * <p>
 * The binds deliberately go through {@code bindBuffer} rather than raw GL: Celeritas caches which
 * buffer is bound per target, and binding behind its back would make it skip a later bind that
 * actually matters. Binding to {@code ARRAY_BUFFER} also avoids the GL 3.1-only
 * {@code GL_COPY_READ_BUFFER} target that the original method uses.
 */
@Mixin(targets = "org.embeddedt.embeddium.impl.gl.device.GLRenderDevice$ImmediateCommandList",
       remap = false)
public abstract class MixinImmediateCommandList {

    @Shadow
    public abstract void bindBuffer(GlBufferTarget target, GlBuffer buffer);

    /**
     * Scratch memory for the emulated copy, grown on demand and reused.
     * <p>
     * Not synchronised, and it does not need to be: this is the immediate command list, which
     * Celeritas only ever drives from the render thread inside {@code RenderDevice} managed code.
     */
    @Unique
    private static ByteBuffer natron$staging;

    @Unique
    private static ByteBuffer natron$staging(int bytes) {
        if (natron$staging == null || natron$staging.capacity() < bytes) {
            natron$staging = BufferUtils.createByteBuffer(bytes);
        }
        natron$staging.position(0);
        natron$staging.limit(bytes);
        return natron$staging;
    }

    /**
     * @author natron
     * @reason Fall back to a system-memory round trip on drivers without glCopyBufferSubData,
     *         which is every macOS build of 1.8.9 since it only ever gets an OpenGL 2.1 context.
     */
    @Overwrite
    public void copyBufferSubData(GlBuffer src, GlBuffer dst, long readOffset, long writeOffset,
                                  long bytes) {
        if (NatronGlCaps.hasNativeBufferCopy()) {
            // Vanilla Celeritas path: stays entirely on the GPU.
            this.bindBuffer(GlBufferTarget.COPY_READ_BUFFER, src);
            this.bindBuffer(GlBufferTarget.COPY_WRITE_BUFFER, dst);
            LWJGLServiceProvider.LWJGL.glCopyBufferSubData(
                GlBufferTarget.COPY_READ_BUFFER.getTargetParameter(),
                GlBufferTarget.COPY_WRITE_BUFFER.getTargetParameter(),
                readOffset, writeOffset, bytes);
            return;
        }

        final ByteBuffer staging = natron$staging((int) bytes);

        this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, src);
        GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, readOffset, staging);

        this.bindBuffer(GlBufferTarget.ARRAY_BUFFER, dst);
        staging.position(0);
        staging.limit((int) bytes);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, writeOffset, staging);
    }
}
