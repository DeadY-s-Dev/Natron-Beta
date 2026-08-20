package me.natron.render;

import java.nio.FloatBuffer;

import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.embeddedt.embeddium.impl.render.viewport.frustum.SimpleFrustum;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * Builds the {@link Viewport} Celeritas culls against out of 1.8.9's camera.
 * <p>
 * Celeritas expects a JOML frustum. 1.8.9 keeps the camera in GL's fixed-function matrix stack and
 * hands mods an {@code ICamera} built from those matrices, so rather than translating vanilla's
 * {@code ClippingHelper} planes the matrices are read back and multiplied - JOML derives the same
 * six planes from the clip matrix, which is what vanilla does internally anyway.
 * <p>
 * Takes the projection/modelview as already-read buffers rather than querying GL itself.
 * {@code glGetFloatv(GL_PROJECTION_MATRIX/MODELVIEW_MATRIX)} is a well-known synchronization point
 * on several drivers - it can force the driver to flush and wait for the GPU, which is disastrous
 * to call more than once per frame. This used to query GL on every call; folding it into a single
 * per-frame read (done once in the render-global hook and shared with
 * {@link NatronWorldRenderer#createChunkRenderMatrices()}) cut the query count from 10/frame to 2.
 */
public final class ViewportBuilder {

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Matrix4f clip = new Matrix4f();

    private final FrustumIntersection intersection = new FrustumIntersection();

    public Viewport create(FloatBuffer projectionBuffer, FloatBuffer modelViewBuffer,
                           double cameraX, double cameraY, double cameraZ) {
        projectionBuffer.rewind();
        modelViewBuffer.rewind();

        this.projection.set(projectionBuffer);
        this.modelView.set(modelViewBuffer);

        // GL matrices are column-major and so is JOML's set(FloatBuffer), so no transpose here.
        this.projection.mul(this.modelView, this.clip);
        this.intersection.set(this.clip);

        return new Viewport(new SimpleFrustum(this.intersection), new Vector3d(cameraX, cameraY, cameraZ));
    }
}
