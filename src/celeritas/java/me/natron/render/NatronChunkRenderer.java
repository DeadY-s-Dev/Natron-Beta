package me.natron.render;

import org.embeddedt.embeddium.impl.gl.device.RenderDevice;
import org.embeddedt.embeddium.impl.render.chunk.DefaultChunkRenderer;
import org.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderTextureSlot;

/**
 * Port of Angelica's {@code AngelicaChunkRenderer}.
 * <p>
 * Angelica's version is 270 lines, but nearly all of that is swapping in Iris shader programs and
 * managing shadow passes. {@link DefaultChunkRenderer} has exactly one abstract method, so without
 * shaders the whole subclass is the texture unit binding: unit 0 is the block atlas, unit 1 is the
 * lightmap - the same assignment 1.8.9 uses for terrain.
 */
public class NatronChunkRenderer extends DefaultChunkRenderer {

    public NatronChunkRenderer(RenderDevice device, RenderPassConfiguration<?> configuration) {
        super(device, configuration);
    }

    @Override
    protected void configureShaderInterface(ChunkShaderInterface shader) {
        shader.setTextureSlot(ChunkShaderTextureSlot.BLOCK, 0);
        shader.setTextureSlot(ChunkShaderTextureSlot.LIGHT, 1);
    }
}
