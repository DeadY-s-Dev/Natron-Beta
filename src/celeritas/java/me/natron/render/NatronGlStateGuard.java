package me.natron.render;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.embeddedt.embeddium.impl.gl.device.RenderDevice;
import org.lwjgl.opengl.GL11;

/**
 * Wraps a Celeritas managed-code block so vanilla's own {@code GlStateManager} cache is never left
 * pointing at stale state afterwards.
 * <p>
 * Vanilla's {@code GlStateManager} is a cache in front of GL: {@code bindTexture()},
 * {@code enableBlend()}, {@code depthMask()} and friends all compare against a remembered value
 * and skip the real GL call when nothing looks like it changed. Celeritas has no idea that cache
 * exists - it drives GL directly through its own device/state tracker. So after Celeritas draws a
 * terrain layer, vanilla's cache is still describing whatever GL state was true *before* Celeritas
 * ran, while the driver has actually moved on. The next thing vanilla asks for that happens to
 * match its stale cache gets silently skipped, and the real GL state - Celeritas's, not vanilla's -
 * stays in effect. Entities rendered right after terrain (which vanilla does, between the opaque
 * and translucent layers) inherit whatever blend/depth/texture state Celeritas left behind.
 * <p>
 * There is no supported way to invalidate that cache from outside - most of the fields are private
 * booleans, and a boolean cache can't be forced to "unknown". What glPushAttrib/glPopAttrib
 * guarantee instead is that the *real* GL state is bit-for-bit what it was before this block ran,
 * which means vanilla's cache - describing that same prior state - is still correct once the block
 * exits. Restoring reality to match the cache, rather than trying to invalidate the cache to match
 * reality.
 * <p>
 * The one thing glPushAttrib is documented to restore but has had inconsistent driver support for
 * historically is the active texture unit selector (glActiveTexture) - it was bolted onto
 * GL_TEXTURE_BIT after the fact by ARB_multitexture, years after glPushAttrib's own design.
 * Forcing it to unit 0 here, right after the push, costs nothing (push already captured whatever
 * unit was really active) and means every managed block - not just the one that happens to draw
 * terrain - starts from the same known baseline instead of trusting the driver to have gotten that
 * one detail right.
 * <p>
 * <b>Why not {@code GL_ALL_ATTRIB_BITS}.</b> This guard opens up to five times a frame - once
 * around {@code setupTerrain}, once around each of up to four {@code renderBlockLayer} calls - so
 * unlike almost everything else in this port, its cost is not confined to world load. It runs every
 * frame, forever, whether or not a single chunk is being built. {@code glPushAttrib} pushing the
 * full attribute stack is a well-documented expensive call precisely because {@code
 * GL_ALL_ATTRIB_BITS} captures roughly twenty state groups, and most of them protect nothing here:
 * accumulation buffer, evaluators, hints, line/point rasterization, display lists and pixel transfer
 * are all fixed-function features nothing in this shader-driven pipeline has any reason to touch,
 * and none of them are fields vanilla's own {@code GlStateManager} (read in full:
 * {@code net.minecraft.client.renderer.GlStateManager}) caches either.
 * <p>
 * The mask below covers every group a field of {@code GlStateManager} actually falls under, derived
 * by reading its full field list rather than assumed:
 * <ul>
 *   <li>{@code GL_ENABLE_BIT} - every glEnable/glDisable boolean {@code GlStateManager} tracks
 *       (alpha test, lighting, all 8 lights, color material, blend, depth test, fog, cull face,
 *       polygon offset fill/line, color logic op, texgen S/T/R/Q, texture 2D, normalize, rescale
 *       normal) in one group, regardless of which domain each belongs to.</li>
 *   <li>{@code GL_COLOR_BUFFER_BIT} - alphaFunc/ref, blendFunc(Separate), colorLogicOp, colorMask,
 *       clearColor.</li>
 *   <li>{@code GL_LIGHTING_BIT} - colorMaterial face/mode, shadeModel.</li>
 *   <li>{@code GL_DEPTH_BUFFER_BIT} - depthFunc, clearDepth.</li>
 *   <li>{@code GL_FOG_BIT} - fog mode/density/start/end.</li>
 *   <li>{@code GL_POLYGON_BIT} - cullFace mode, polygonOffset factor/units.</li>
 *   <li>{@code GL_TEXTURE_BIT} - texGen coordinate params, the active texture unit, every unit's
 *       bound texture name.</li>
 *   <li>{@code GL_STENCIL_BUFFER_BIT} - stencil func/ref/mask and the three stencil ops.</li>
 *   <li>{@code GL_CURRENT_BIT} - the current color from {@code glColor4f}.</li>
 * </ul>
 * Two more are kept in even though {@code GlStateManager} caches neither: {@code GL_VIEWPORT_BIT}
 * and {@code GL_SCISSOR_BIT}. Their on/off flags are already covered by {@code GL_ENABLE_BIT}, but
 * their parameters (the viewport rectangle, the scissor box) are not GlStateManager's to leave
 * stale - they are ambient GL state anything drawn afterwards inherits directly, cache or no cache,
 * and confirming Celeritas's draw path never touches either would mean reading every line of it
 * rather than its dependency on {@code GlStateManager}'s specific fields, which is what the rest of
 * this list is actually verified against. Two more groups is a small enough addition to keep rather
 * than spend that reading finding out either way.
 * <p>
 * Vanilla has its own narrower {@code GlStateManager.pushAttrib()}, using {@code GL_ENABLE_BIT |
 * GL_LIGHTING_BIT} alone - too narrow to reuse here, since it restores whether blending/texturing/
 * fog are on but not the blend function, bound texture, or fog parameters Celeritas may have left
 * pointed somewhere else. Its existence is what confirms restoring less than everything is the
 * normal way to use this call, not a shortcut invented for this port.
 */
public final class NatronGlStateGuard implements AutoCloseable {

    /** See the class comment for how this was derived from {@code GlStateManager}'s own fields. */
    private static final int RESTORE_MASK = GL11.GL_ENABLE_BIT
        | GL11.GL_COLOR_BUFFER_BIT
        | GL11.GL_LIGHTING_BIT
        | GL11.GL_DEPTH_BUFFER_BIT
        | GL11.GL_FOG_BIT
        | GL11.GL_POLYGON_BIT
        | GL11.GL_TEXTURE_BIT
        | GL11.GL_STENCIL_BUFFER_BIT
        | GL11.GL_CURRENT_BIT
        | GL11.GL_VIEWPORT_BIT
        | GL11.GL_SCISSOR_BIT;

    private NatronGlStateGuard() {
        GL11.glPushAttrib(RESTORE_MASK);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        RenderDevice.enterManagedCode();
    }

    public static NatronGlStateGuard open() {
        return new NatronGlStateGuard();
    }

    @Override
    public void close() {
        RenderDevice.exitManagedCode();
        GL11.glPopAttrib();
    }
}
