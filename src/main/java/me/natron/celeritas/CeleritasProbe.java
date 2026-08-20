package me.natron.celeritas;

import java.util.ServiceLoader;

import me.natron.NatronDiagnosticLog;

/**
 * Loads the downgraded Celeritas classes to prove the Java 17 -> 8 conversion produced
 * bytecode that 1.8.9's JVM actually accepts.
 * <p>
 * This is a checkpoint, not a feature: nothing here renders anything. Celeritas is the
 * Minecraft-agnostic half of the renderer, and wiring it to 1.8.9 still needs the whole
 * version-specific bridge (world slice, mesher, chunk tracker) that Angelica implements
 * against 1.7.10 in {@code com.gtnewhorizons.angelica.rendering.celeritas}.
 * <p>
 * Every line here goes through {@link NatronDiagnosticLog} rather than the logger directly.
 * This used to be gated behind {@code -Dnatron.verifyMixins=true} as well, on top of that -
 * two reasons testing kept coming back with none of this in evidence, not one.
 */
public final class CeleritasProbe {

    /** Core of the terrain renderer — the classes a 1.8.9 bridge would have to drive. */
    private static final String[] CORE_CLASSES = {
        "org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager",
        "org.embeddedt.embeddium.impl.render.chunk.RenderSection",
        "org.embeddedt.embeddium.impl.render.chunk.DefaultChunkRenderer",
        "org.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkBuilder",
        "org.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList",
        "org.embeddedt.embeddium.impl.gl.device.RenderDevice",
        "org.embeddedt.embeddium.impl.gl.arena.GlBufferArena",
        "org.embeddedt.embeddium.impl.render.viewport.Viewport",
    };

    private CeleritasProbe() {
    }

    /**
     * Every GL entry point Celeritas needs that 1.8.9's LWJGL 2 backend can only satisfy through a
     * core version or an ARB/EXT extension. On a driver that offers neither, the backend throws
     * {@code UnsupportedOperationException} the first time a chunk mesh is uploaded or drawn.
     * <p>
     * Left in because "which of these does this machine actually have" is the first question worth
     * asking whenever the renderer refuses to start, and guessing at it wasted a debugging cycle.
     */
    private static final String[][] REQUIRED_CAPABILITIES = {
        { "OpenGL30", "GL_APPLE_vertex_array_object", "GL_ARB_vertex_array_object" },
        { "OpenGL31", "GL_ARB_copy_buffer" },
        { "OpenGL32", "GL_ARB_draw_elements_base_vertex" },
        { "OpenGL30", "GL_EXT_gpu_shader4" },
        { "OpenGL33", "GL_ARB_instanced_arrays" },
        { "OpenGL43", "GL_ARB_multi_draw_indirect" },
        // Decides the mesh upload path. With it, RenderRegionManager picks MappedStagingBuffer and
        // writes straight into persistently mapped GPU memory; without it, every upload goes
        // through FallbackStagingBuffer and a buffer-to-buffer copy. Same output, very different
        // per-upload cost - and it scales with how many sections finish in a frame, so a machine on
        // the fallback path pays it hardest exactly when the most chunks are being built.
        { "OpenGL44", "GL_ARB_buffer_storage" },
    };

    private static void reportGlCapabilities() {
        try {
            final Object caps = Class.forName("org.lwjgl.opengl.GLContext")
                .getMethod("getCapabilities").invoke(null);

            for (String[] group : REQUIRED_CAPABILITIES) {
                final StringBuilder sb = new StringBuilder();
                boolean anyPresent = false;

                for (String name : group) {
                    boolean present = false;
                    try {
                        present = caps.getClass().getField(name).getBoolean(caps);
                    } catch (NoSuchFieldException ignored) {
                        // Older LWJGL builds simply do not know this capability.
                    }
                    anyPresent |= present;
                    sb.append(name).append('=').append(present).append(' ');
                }

                NatronDiagnosticLog.write(String.format("[celeritas] gl cap %s: %s",
                    anyPresent ? "OK  " : "MISS", sb.toString().trim()));
            }
        } catch (Throwable t) {
            NatronDiagnosticLog.writeError("[celeritas] could not read GL capabilities", t);
        }
    }

    public static void probe() {
        final ClassLoader loader = CeleritasProbe.class.getClassLoader();

        // Celeritas is bundled only when -PincludeCeleritas=true; say so plainly instead of
        // logging eight stack traces.
        try {
            Class.forName(CORE_CLASSES[0], false, loader);
        } catch (Throwable t) {
            NatronDiagnosticLog.write(
                "[celeritas] not bundled (build with -PincludeCeleritas=true)");
            return;
        }

        int loaded = 0;
        for (String name : CORE_CLASSES) {
            try {
                Class.forName(name, false, loader);
                loaded++;
            } catch (Throwable t) {
                NatronDiagnosticLog.writeError("[celeritas] could not load " + name, t);
            }
        }
        NatronDiagnosticLog.write(String.format("[celeritas] core classes loaded: %d/%d",
            Integer.valueOf(loaded), Integer.valueOf(CORE_CLASSES.length)));

        // Linking a class only proves the format; running one proves the downgraded
        // bytecode verifies and executes. MathUtil is pure arithmetic with no GL context.
        try {
            final Class<?> mathUtil = Class.forName(
                "org.embeddedt.embeddium.impl.common.util.MathUtil", true, loader);
            NatronDiagnosticLog.write(String.format("[celeritas] initialised %s (methods: %d)",
                mathUtil.getSimpleName(), Integer.valueOf(mathUtil.getDeclaredMethods().length)));
        } catch (Throwable t) {
            NatronDiagnosticLog.writeError("[celeritas] MathUtil failed to initialise", t);
        }

        // The whole point of extracting from Angelica rather than Maven: this must resolve to
        // the LWJGL 2 backend, because 1.8.9 has no LWJGL 3 on the classpath at all.
        try {
            final Class<?> provider = Class.forName(
                "com.mitchej123.lwjgl.LWJGLServiceProvider", true, loader);
            final Object service = provider.getField("LWJGL").get(null);
            final int pointerSize = provider.getField("POINTER_SIZE").getInt(null);
            NatronDiagnosticLog.write(String.format(
                "[celeritas] LWJGL backend: %s (pointer size %d)",
                service.getClass().getName(), Integer.valueOf(pointerSize)));
        } catch (Throwable t) {
            NatronDiagnosticLog.writeError("[celeritas] LWJGL backend resolution failed", t);
        }

        // Angelica implements GLStateManagerService with its own 46k-line glsm subsystem, because
        // 1.7.10 has no GlStateManager at all. 1.8.9 ships one, and Celeritas turns out to
        // reference glsm zero times - so the stock pass-through should satisfy the contract.
        // Resolving it here is what turns that reading of the code into a fact.
        try {
            final Class<?> provider = Class.forName(
                "com.mitchej123.glsm.GLStateManagerServiceProvider", true, loader);
            final Object service = provider.getField("GL_STATE_MANAGER").get(null);
            NatronDiagnosticLog.write(
                "[celeritas] GL state manager: " + service.getClass().getName());
        } catch (Throwable t) {
            NatronDiagnosticLog.writeError("[celeritas] GL state manager resolution failed", t);
        }

        // First piece of the bridge: Celeritas looks the fog implementation up by ServiceLoader,
        // so this proves our own class is both found and constructible inside the game.
        try {
            final Class<?> fogService = Class.forName(
                "org.embeddedt.embeddium.impl.render.chunk.fog.FogService", false, loader);
            int found = 0;
            String name = "none";
            for (Object impl : ServiceLoader.load(fogService, loader)) {
                found++;
                name = impl.getClass().getName();
            }
            NatronDiagnosticLog.write(String.format("[celeritas] fog service: %s (%d found)",
                name, Integer.valueOf(found)));
        } catch (Throwable t) {
            NatronDiagnosticLog.writeError("[celeritas] fog service lookup failed", t);
        }

        reportGlCapabilities();

        // The actual verdict, not just the raw capability dump above: does this machine clear the
        // hard-required bar at all. Reflective like everything else here, because this class
        // compiles with or without -PincludeCeleritas, and NatronCeleritasAvailability only
        // exists when it is on.
        try {
            final Class<?> availability = Class.forName(
                "me.natron.render.NatronCeleritasAvailability", true, loader);
            final boolean supported = ((Boolean) availability.getMethod("isSupported")
                .invoke(null)).booleanValue();

            if (supported) {
                NatronDiagnosticLog.write("[celeritas] availability: supported");
            } else {
                final String reason = (String) availability.getMethod("unsupportedReason")
                    .invoke(null);
                NatronDiagnosticLog.write(
                    "[celeritas] availability: NOT supported, falling back to vanilla (" + reason
                        + ")");
            }
        } catch (Throwable t) {
            NatronDiagnosticLog.writeError("[celeritas] availability check failed", t);
        }

        // SimpleWorldRenderer casts the world straight to ChunkTrackerHolder, so if the mixin did
        // not weave, terrain setup dies with a ClassCastException on the first frame in a world.
        try {
            final Class<?> worldClient = Class.forName(
                "net.minecraft.client.multiplayer.WorldClient", false, loader);
            final Class<?> holder = Class.forName(
                "org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder", false, loader);
            NatronDiagnosticLog.write(
                "[celeritas] WorldClient implements ChunkTrackerHolder: "
                    + holder.isAssignableFrom(worldClient));
        } catch (Throwable t) {
            NatronDiagnosticLog.writeError("[celeritas] chunk tracker check failed", t);
        }

        // The renderer reaches the GPU through RenderDevice, not through the state manager.
        // Constructing it proves the GL device layer stands up on 1.8.9's LWJGL 2 context.
        try {
            final Class<?> renderDevice = Class.forName(
                "org.embeddedt.embeddium.impl.gl.device.RenderDevice", true, loader);
            final Object instance = renderDevice.getField("INSTANCE").get(null);
            NatronDiagnosticLog.write(
                "[celeritas] render device: " + instance.getClass().getName());

            // Which upload path RenderRegionManager will actually pick, asked the same way it
            // asks. Worth stating outright rather than inferring from the capability list above.
            final Class<?> mapped = Class.forName(
                "org.embeddedt.embeddium.impl.gl.arena.staging.MappedStagingBuffer", true, loader);
            final boolean fastUpload = ((Boolean) mapped
                .getMethod("isSupported", renderDevice)
                .invoke(null, instance)).booleanValue();

            NatronDiagnosticLog.write("[celeritas] mesh upload path: "
                + (fastUpload ? "MappedStagingBuffer (persistent mapped)"
                    : "FallbackStagingBuffer (buffer copy - slower per upload)"));
        } catch (Throwable t) {
            NatronDiagnosticLog.writeError("[celeritas] render device init failed", t);
        }
    }
}
