package me.natron.render;

/**
 * Driver capability answers that change which code path the renderer takes, resolved once and
 * cached.
 * <p>
 * These live outside any mixin on purpose. Two separate mixins need the same answer, and a
 * {@code @Unique} static inside a mixin is copied into its target class rather than shared, so
 * each would resolve and cache its own copy.
 */
public final class NatronGlCaps {

    private static Boolean nativeBufferCopy;
    private static Boolean persistentMapping;

    private NatronGlCaps() {
    }

    /**
     * True if any capability in {@code names} is present. Unknown names read as absent.
     * <p>
     * Package-private rather than private: {@link NatronCeleritasAvailability} asks this same
     * question about a different, non-overlapping set of capabilities (the ones with no fallback
     * at all, versus the two here that pick between two working paths), and duplicating the
     * reflection lookup would be the same code lying twice.
     */
    static boolean anyOf(String... names) {
        try {
            final Object caps = Class.forName("org.lwjgl.opengl.GLContext")
                .getMethod("getCapabilities").invoke(null);

            for (String name : names) {
                try {
                    if (caps.getClass().getField(name).getBoolean(caps)) {
                        return true;
                    }
                } catch (NoSuchFieldException ignored) {
                    // Older LWJGL builds simply do not know this capability.
                }
            }
        } catch (Throwable ignored) {
            // Treat an unreadable capability set as "not supported" and take the safe path.
        }
        return false;
    }

    /**
     * Whether the driver can copy buffer-to-buffer on the GPU, i.e. offers
     * {@code glCopyBufferSubData} through OpenGL 3.1 or {@code ARB_copy_buffer}.
     * <p>
     * Without it the fallback mesh upload path would otherwise have to round-trip every section
     * through system memory. See {@code MixinFallbackStagingBuffer} for what is done instead.
     */
    public static boolean hasNativeBufferCopy() {
        if (nativeBufferCopy == null) {
            nativeBufferCopy = Boolean.valueOf(anyOf("OpenGL31", "GL_ARB_copy_buffer"));
        }
        return nativeBufferCopy.booleanValue();
    }

    /**
     * Whether mesh uploads go through {@code MappedStagingBuffer} rather than
     * {@code FallbackStagingBuffer}.
     * <p>
     * Asked the same way {@code MappedStagingBuffer.isSupported} asks it, so the two cannot
     * disagree - read here rather than called there because the caller wants the answer before it
     * has a {@code RenderDevice}. On the mapped path an upload writes into already-mapped GPU
     * memory; on the fallback path it is a buffer upload plus a copy. Same result, different enough
     * in cost that it decides how much uploading one frame should attempt.
     */
    public static boolean hasPersistentMapping() {
        if (persistentMapping == null) {
            persistentMapping = Boolean.valueOf(anyOf("OpenGL44", "GL_ARB_buffer_storage"));
        }
        return persistentMapping.booleanValue();
    }
}
