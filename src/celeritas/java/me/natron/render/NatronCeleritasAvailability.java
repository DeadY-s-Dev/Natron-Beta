package me.natron.render;

import me.natron.Natron;
import me.natron.NatronDiagnosticLog;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

/**
 * Whether this machine may run Celeritas at all, checked once before anything Celeritas-specific
 * is constructed.
 * <p>
 * {@link NatronGlCaps} answers a different question: which of two working paths an upload
 * should take. Both of its answers render correctly, just at different speeds, because a real
 * fallback exists for both (see {@code MixinFallbackStagingBuffer},
 * {@code MixinImmediateCommandList}). That is deliberately why {@code ARB_copy_buffer} and
 * {@code ARB_buffer_storage} are absent from the list below: a driver missing only those two runs
 * Celeritas correctly, just through the slower path, and gating on them here would refuse
 * machines that actually work fine.
 * <p>
 * The list below is short, and deliberately not just a copy of
 * {@link me.natron.celeritas.CeleritasProbe}'s wider diagnostic list - that list documents
 * everything Celeritas as a whole can reach across every feature; this one is only what this
 * port's own code path actually calls. Celeritas offers an indirect multi-draw emitter
 * ({@code GL_ARB_multi_draw_indirect}, OpenGL 4.3) and instanced vertex attributes
 * ({@code GL_ARB_instanced_arrays}), but {@link NatronChunkRenderer} constructs
 * {@code DefaultChunkRenderer} with its default emitter - {@code DirectMultiDrawEmitter}, which
 * calls {@code glMultiDrawElementsBaseVertex} (OpenGL 3.2) instead - and
 * {@code CompactChunkVertex}, the vertex format this port uses, never sets a divisor on any of its
 * four attributes, so {@code glVertexAttribDivisor} is never reached either. Requiring either
 * capability would have refused machines this port never actually needed them from. Likewise
 * {@code GL_EXT_gpu_shader4}: none of the bundled shaders declare it with {@code #extension} -
 * they are plain {@code #version 330 core} and {@code #version 150}, which is exactly what
 * {@code OpenGL33} below already covers.
 * <p>
 * What remains has no fallback and is genuinely reached on every frame. Vertex array objects and
 * base-vertex indexed drawing are load-bearing for how Celeritas issues every draw call; GLSL 330
 * core is what the bundled terrain shader is written in, with no lower-version variant to fall
 * back to. A driver missing any of these cannot run Celeritas at all. Left unchecked, that machine
 * would not fail here; it would fail somewhere down inside Celeritas the first time the missing
 * feature was reached - a poor place to have to explain the problem, both because it is far from
 * the field that decides it, and because whichever unlucky call happens to reach it first differs
 * by machine.
 * <p>
 * Checking here instead means the one call site that would construct
 * {@link NatronWorldRenderer} never does. Every other injection in
 * {@code MixinRenderGlobalCeleritas} already checks {@code getInstanceOrNull() != null} and
 * no-ops when it is null - that check was already load-bearing for the ordinary case of a world
 * not being loaded yet, and it turns out to be exactly what an unsupported machine needs too.
 * Vanilla's own terrain renderer, completely untouched, runs instead.
 */
public final class NatronCeleritasAvailability {

    /** One OpenGL feature Celeritas has no fallback for, and why it is needed. */
    private static final class Requirement {
        final String reason;
        final String[] capabilityNames;

        Requirement(String reason, String... capabilityNames) {
            this.reason = reason;
            this.capabilityNames = capabilityNames;
        }
    }

    private static final Requirement[] REQUIREMENTS = {
        new Requirement("vertex array objects",
            "OpenGL30", "GL_APPLE_vertex_array_object", "GL_ARB_vertex_array_object"),
        new Requirement("base-vertex indexed drawing",
            "OpenGL32", "GL_ARB_draw_elements_base_vertex"),
        // No extension name to fall back to: GLSL version support tracks the context version
        // directly, so OpenGL33 is the only field that answers this.
        new Requirement("GLSL 330 core shaders", "OpenGL33"),
    };

    private static Boolean supported;
    private static String reason = "";
    private static boolean warned;

    private NatronCeleritasAvailability() {
    }

    /** Resolved once and cached: GL capabilities do not change during a session. */
    public static boolean isSupported() {
        if (supported == null) {
            final StringBuilder missing = new StringBuilder();

            for (Requirement requirement : REQUIREMENTS) {
                if (!NatronGlCaps.anyOf(requirement.capabilityNames)) {
                    if (missing.length() > 0) {
                        missing.append(", ");
                    }
                    missing.append(requirement.reason);
                }
            }

            reason = missing.toString();
            supported = Boolean.valueOf(reason.isEmpty());
        }

        return supported.booleanValue();
    }

    /** Empty when {@link #isSupported()} is true. */
    public static String unsupportedReason() {
        isSupported();
        return reason;
    }

    /**
     * Logs and tells the player why Celeritas is off. Safe to call every time a world loads -
     * {@code setWorldAndLoadRenderers} fires on every dimension change, not just once - but only
     * the first call in a session does anything, so neither the log nor the player's chat repeats
     * it on every nether portal.
     */
    public static void warnOnceIfUnsupported() {
        if (isSupported() || warned) {
            return;
        }
        warned = true;

        NatronDiagnosticLog.write("[celeritas] disabled - this GPU/driver is missing: " + reason
            + ". Falling back to vanilla terrain rendering.");

        final EntityPlayer player = Minecraft.getMinecraft().thePlayer;

        if (player != null) {
            player.addChatMessage(new ChatComponentText(
                Natron.NAME + ": disabled on this GPU (missing " + reason
                    + "). Using vanilla terrain rendering."));
        }
    }
}
