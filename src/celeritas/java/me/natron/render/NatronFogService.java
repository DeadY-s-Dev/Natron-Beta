package me.natron.render;

import java.nio.FloatBuffer;

import org.embeddedt.embeddium.impl.render.chunk.fog.FogService;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkFogMode;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Port of Angelica's {@code AngelicaFogService}.
 * <p>
 * Angelica reads fog through its own {@code GLStateManager.getFogState()}, which exists because
 * 1.7.10 has no GlStateManager to ask. 1.8.9 does have one and it tracks fog, but its
 * {@code fogState} field is private, and adding an accessor mixin just to read six numbers once
 * per render pass is not worth it - the driver is asked directly instead.
 * <p>
 * "Once per render pass" undersold it. Every method here used to query the driver fresh on every
 * call, and {@code ChunkShaderFogComponent} - Celeritas's own code, not this port's - calls
 * {@code getFogMode()} once per {@code begin(pass)} just to build the shader cache key, then calls
 * the rest of these through whichever {@code setup()} that mode resolves to. {@code begin(pass)}
 * runs once per terrain pass, and a frame draws up to four (solid, cutout and cutout-mipped both
 * routing through the same pass, translucent) - so this was up to five {@code glGet}/{@code
 * glIsEnabled} calls times four passes, calling the driver up to twenty times a frame for state
 * that cannot have changed since the frame started. Exactly the class of bug the per-layer camera
 * matrix reads and the per-pass alpha baseline reads turned out to be, just with the widest count
 * of the three once it was actually looked for.
 * <p>
 * Fixed the same way both of those were: read once per frame, into fields, and hand those back
 * instead of asking the driver again. {@link #refresh()} is called from the same {@code
 * setupTerrain} hook that already refreshes the camera matrices and the alpha baseline, so all
 * three per-frame reads happen in one place.
 */
public class NatronFogService implements FogService {

    /**
     * Static rather than per-instance: {@code ChunkShaderFogComponent.FOG_SERVICE} is resolved once
     * via {@code ServiceLoader} and held there for the life of the game, so exactly one
     * {@link NatronFogService} instance ever exists - but this class has no reference back to
     * that instance to call a refresh method on, only the reverse. Statics let the per-frame hook
     * refresh the cache without needing one.
     */
    private static float fogStart;
    private static float fogEnd;
    private static float fogDensity;
    private static final float[] fogColor = new float[4];
    private static ChunkFogMode fogMode = ChunkFogMode.NONE;

    private static final FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(16);

    /** Reads all six values once. Call at most once per frame, before any pass draws. */
    public static void refresh() {
        fogStart = GL11.glGetFloat(GL11.GL_FOG_START);
        fogEnd = GL11.glGetFloat(GL11.GL_FOG_END);
        fogDensity = GL11.glGetFloat(GL11.GL_FOG_DENSITY);

        colorBuffer.clear();
        GL11.glGetFloat(GL11.GL_FOG_COLOR, colorBuffer);
        colorBuffer.get(fogColor, 0, 4);

        fogMode = GL11.glIsEnabled(GL11.GL_FOG)
            ? ChunkFogMode.fromGLMode(GL11.glGetInteger(GL11.GL_FOG_MODE))
            : ChunkFogMode.NONE;
    }

    @Override
    public float getFogStart() {
        return fogStart;
    }

    @Override
    public float getFogEnd() {
        return fogEnd;
    }

    @Override
    public float getFogDensity() {
        return fogDensity;
    }

    /** 1.8.9 has a single fog shape, same as 1.7.10. */
    @Override
    public int getFogShapeIndex() {
        return 0;
    }

    @Override
    public float getFogCutoff() {
        return fogEnd;
    }

    @Override
    public float[] getFogColor() {
        return fogColor;
    }

    @Override
    public ChunkFogMode getFogMode() {
        return fogMode;
    }
}
