package me.natron.render;

import net.minecraft.client.renderer.OpenGlHelper;
import org.embeddedt.embeddium.impl.gl.device.GLRenderDevice;

/**
 * Port of Angelica's {@code CeleritasSetup}.
 * <p>
 * Celeritas ships {@code VANILLA_STATE_RESETTER} as a stub that throws, because only the host mod
 * knows what state the game leaves the GL context in. It runs whenever the renderer enters managed
 * code, and its job is to undo whatever vanilla left bound so Celeritas can start from a known
 * point. Angelica unbinds the array buffer; 1.8.9 needs the same, just through
 * {@link OpenGlHelper} so the ARB fallback path is respected.
 */
public final class NatronCeleritasSetup {

    private static boolean initialized;

    private NatronCeleritasSetup() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        GLRenderDevice.VANILLA_STATE_RESETTER = new Runnable() {
            @Override
            public void run() {
                OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
            }
        };
    }
}
