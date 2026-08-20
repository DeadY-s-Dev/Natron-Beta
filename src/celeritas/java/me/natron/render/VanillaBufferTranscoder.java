package me.natron.render;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import org.embeddedt.embeddium.impl.render.chunk.compile.buffers.ChunkModelBuilder;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;

/**
 * Moves geometry out of a vanilla {@code WorldRenderer} buffer and into Celeritas's chunk mesh
 * builders.
 * <p>
 * This is the one place the two worlds meet, and they line up almost exactly.
 * {@code DefaultVertexFormats.BLOCK} is 28 bytes per vertex:
 *
 * <pre>
 *   offset  0  POSITION_3F   3 x float     -> Vertex.x/y/z
 *   offset 12  COLOR_4UB     4 x ubyte     -> Vertex.color   (copied as one int)
 *   offset 16  TEX_2F        2 x float     -> Vertex.u/v
 *   offset 24  TEX_2S        2 x short     -> Vertex.light   (copied as one int)
 * </pre>
 *
 * Celeritas's {@code CompactChunkVertex} writes {@code light} back out as two unsigned shorts, so
 * the lightmap word survives verbatim - no unpacking or rescaling.
 * <p>
 * The only thing vanilla's flat buffer does not carry is which face each quad belongs to, which
 * Celeritas needs in order to bucket vertices for backface culling. That is recovered from the
 * geometry: the quad's normal is computed and snapped to its dominant axis.
 */
public final class VanillaBufferTranscoder {

    private static final int VERTICES_PER_QUAD = 4;

    /**
     * Layout of {@code DefaultVertexFormats.BLOCK}, read from the format itself rather than
     * written down here.
     * <p>
     * The stock layout is 28 bytes - {@code POSITION_3F} at 0, {@code COLOR_4UB} at 12,
     * {@code TEX_2F} at 16, {@code TEX_2S} at 24 - and these constants used to be spelled out as
     * exactly that. They are read at runtime now because anything that edits the block vertex
     * format edits it for the whole game, and this class would then be decoding the wrong bytes at
     * the wrong offsets with nothing to indicate why the terrain had turned to noise. OptiFine is
     * the obvious case, since it patches vanilla's rendering classes wholesale, but a Forge mod
     * can extend the format too.
     * <p>
     * This used to redo the {@link #offsetOf} scan in the constructor - every section rebuild, not
     * once for the game - because a {@code static} field read once at class-load cannot see
     * OptiFine's shader toggle, which is a live, no-restart action that swaps
     * {@code DefaultVertexFormats.BLOCK} for a different instance mid-session. That reasoning about
     * when the format can change was right; caching nothing at all to honour it was not the only
     * way to. A shader toggle is a rare, player-initiated event, while a rebuild is not - so what
     * is worth being current for every time is not "the offsets", it is "whether the format object
     * changed since we last looked" - a single reference comparison. {@link Layout} does that: it
     * re-scans only the first time and immediately after an actual toggle, and reads a settled
     * cache the rest of the time. {@code volatile} is enough to publish it safely across the worker
     * threads that construct a {@link VanillaBufferTranscoder}, and the layout itself is immutable,
     * so nothing about reading it needs to be synchronised.
     */
    private static final class Layout {
        final VertexFormat format;
        final int stride;
        final int offsetPosition;
        final int offsetColor;
        final int offsetTexture;
        final int offsetLight;

        Layout(VertexFormat format) {
            this.format = format;
            this.stride = format.getNextOffset();
            this.offsetPosition = offsetOf(format, DefaultVertexFormats.POSITION_3F);
            this.offsetColor = offsetOf(format, DefaultVertexFormats.COLOR_4UB);
            this.offsetTexture = offsetOf(format, DefaultVertexFormats.TEX_2F);
            this.offsetLight = offsetOf(format, DefaultVertexFormats.TEX_2S);
        }

        /**
         * @return byte offset of {@code element} within {@code format}
         * @throws IllegalStateException if the element is absent, meaning the block format no
         *         longer carries something this transcoder has to read
         */
        private static int offsetOf(VertexFormat format, VertexFormatElement element) {
            for (int i = 0; i < format.getElementCount(); i++) {
                // equals(), not ==: VertexFormatElement compares by type/usage/index/count, and a
                // mod that rebuilds DefaultVertexFormats.BLOCK - which is exactly what OptiFine
                // does - hands back new instances that a reference check would miss even though
                // the layout did not actually change underneath them.
                if (format.getElement(i).equals(element)) {
                    // The format already tracked this when the element was added; asking for it
                    // rather than re-summing getSize() over the preceding elements leaves no room
                    // for the two to disagree.
                    return format.getOffset(i);
                }
            }

            throw new IllegalStateException("DefaultVertexFormats.BLOCK has no " + element
                + "; another mod has changed the block vertex format in a way this renderer "
                + "cannot read");
        }
    }

    /**
     * Racy-single-check by design: two worker threads noticing a format change at the same moment
     * may both build a {@link Layout} and both publish, but both compute the same values from the
     * same {@code DefaultVertexFormats.BLOCK}, so whichever write is last is not wrong, just
     * redundant - a rare, harmless duplication of one small scan, not a correctness risk.
     */
    private static volatile Layout cachedLayout;

    private static Layout currentLayout() {
        final VertexFormat current = DefaultVertexFormats.BLOCK;
        Layout layout = cachedLayout;

        if (layout == null || layout.format != current) {
            layout = new Layout(current);
            cachedLayout = layout;
        }

        return layout;
    }

    /**
     * Not {@code final}, and not set in the constructor, because one transcoder now serves every
     * section a worker builds rather than being rebuilt for each - see
     * {@code NatronChunkBuildContext}.
     * <p>
     * That distinction matters here specifically. A constructor-set layout was only ever correct
     * because a new transcoder was built per section, so the format was re-read often enough by
     * accident; holding one instance across sections turns that into a stale layout the first time
     * OptiFine's shaders are toggled mid-session, which is the exact corruption {@link Layout}
     * exists to prevent. {@link #refreshLayout()} restores the property deliberately, at the same
     * once-per-section frequency the old constructor call had - not more often, which is what
     * resolving it inside {@link #transcode} would have done (up to four times a section, once per
     * layer buffer).
     */
    private Layout layout;

    /** Call once per section build, before any {@link #transcode} call for that section. */
    public void refreshLayout() {
        this.layout = currentLayout();
    }

    private final ChunkVertexEncoder.Vertex[] quad = ChunkVertexEncoder.Vertex.uninitializedQuad();

    /**
     * @param buffer      vanilla's vertex buffer, positioned at the start of the geometry
     * @param vertexCount number of vertices the buffer holds
     * @return how many quads were handed to Celeritas
     */
    public int transcode(ByteBuffer buffer, int vertexCount, ChunkModelBuilder builder, Material material) {
        final int quadCount = vertexCount / VERTICES_PER_QUAD;
        final int base = buffer.position();
        // Hoisted out of the loop below: one field-chain dereference instead of one per vertex.
        final int stride = this.layout.stride;

        for (int q = 0; q < quadCount; q++) {
            final int quadStart = base + q * VERTICES_PER_QUAD * stride;

            for (int v = 0; v < VERTICES_PER_QUAD; v++) {
                readVertex(buffer, quadStart + v * stride, this.quad[v]);
            }

            // One cross product per quad, shared by both consumers below.
            computeNormal(this.quad);

            final int normal = packedNormal();
            for (int v = 0; v < VERTICES_PER_QUAD; v++) {
                this.quad[v].vanillaNormal = normal;
                this.quad[v].trueNormal = normal;
            }

            builder.getVertexBuffer(facingOf()).push(this.quad, material);
        }

        return quadCount;
    }

    private void readVertex(ByteBuffer buffer, int offset, ChunkVertexEncoder.Vertex vertex) {
        final Layout layout = this.layout;

        vertex.x = buffer.getFloat(offset + layout.offsetPosition);
        vertex.y = buffer.getFloat(offset + layout.offsetPosition + 4);
        vertex.z = buffer.getFloat(offset + layout.offsetPosition + 8);
        vertex.color = buffer.getInt(offset + layout.offsetColor);
        vertex.u = buffer.getFloat(offset + layout.offsetTexture);
        vertex.v = buffer.getFloat(offset + layout.offsetTexture + 4);
        vertex.light = buffer.getInt(offset + layout.offsetLight);
    }

    /**
     * The current quad's face normal, held in fields rather than returned in a {@code float[3]}.
     * <p>
     * This used to allocate a {@code float[3]} inside both {@code facingOf} and
     * {@code packedNormal} - two arrays per quad, and the cross product computed twice. A section
     * emits thousands of quads and six workers mesh continuously, so that was millions of
     * short-lived arrays during world load: exactly the allocation rate that turns into visible
     * frame variance once the collector has to keep up with it. Now the normal is computed once
     * per quad into these fields and both consumers read it from there.
     */
    private float normalX;
    private float normalY;
    private float normalZ;

    /**
     * Vanilla does not record a normal in the BLOCK format, so it is derived from the winding:
     * {@code (v1 - v0) x (v3 - v0)}, using opposite corners so degenerate edges are less likely to
     * collapse the result.
     */
    private void computeNormal(ChunkVertexEncoder.Vertex[] quad) {
        final float ax = quad[1].x - quad[0].x;
        final float ay = quad[1].y - quad[0].y;
        final float az = quad[1].z - quad[0].z;

        final float bx = quad[3].x - quad[0].x;
        final float by = quad[3].y - quad[0].y;
        final float bz = quad[3].z - quad[0].z;

        this.normalX = ay * bz - az * by;
        this.normalY = az * bx - ax * bz;
        this.normalZ = ax * by - ay * bx;
    }

    /** Reads the normal computed by the preceding {@link #computeNormal} call. */
    private ModelQuadFacing facingOf() {
        final float nx = this.normalX;
        final float ny = this.normalY;
        final float nz = this.normalZ;

        final float ax = Math.abs(nx);
        final float ay = Math.abs(ny);
        final float az = Math.abs(nz);

        // A quad that is not axis-aligned (a cross, a fence post at an angle) has no single face
        // to be culled against, so it goes into the unassigned bucket like Sodium does.
        if (ax > ay && ax > az) {
            return axisAligned(nx, ay + az, ax) ? (nx > 0 ? ModelQuadFacing.POS_X : ModelQuadFacing.NEG_X)
                : ModelQuadFacing.UNASSIGNED;
        }
        if (ay > az) {
            return axisAligned(ny, ax + az, ay) ? (ny > 0 ? ModelQuadFacing.POS_Y : ModelQuadFacing.NEG_Y)
                : ModelQuadFacing.UNASSIGNED;
        }
        if (az > 0.0F) {
            return axisAligned(nz, ax + ay, az) ? (nz > 0 ? ModelQuadFacing.POS_Z : ModelQuadFacing.NEG_Z)
                : ModelQuadFacing.UNASSIGNED;
        }
        return ModelQuadFacing.UNASSIGNED;
    }

    private static boolean axisAligned(float dominant, float others, float dominantAbs) {
        return dominant != 0.0F && others <= dominantAbs * 1.0E-4F;
    }

    /**
     * Packs the normal the way vanilla's NORMAL_3B element would: one signed byte per axis.
     * Reads the normal computed by the preceding {@link #computeNormal} call.
     */
    private int packedNormal() {
        final float nx = this.normalX;
        final float ny = this.normalY;
        final float nz = this.normalZ;

        final float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length == 0.0F) {
            return 0;
        }

        final int px = (int) (nx / length * 127.0F) & 0xFF;
        final int py = (int) (ny / length * 127.0F) & 0xFF;
        final int pz = (int) (nz / length * 127.0F) & 0xFF;

        return px | py << 8 | pz << 16;
    }
}
