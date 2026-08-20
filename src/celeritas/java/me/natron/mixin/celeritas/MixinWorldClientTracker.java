package me.natron.mixin.celeritas;

import net.minecraft.client.multiplayer.WorldClient;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkStatus;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives {@link WorldClient} the chunk tracker Celeritas expects to find on the world object.
 * <p>
 * {@code SimpleWorldRenderer.setupTerrain} calls {@code ChunkTrackerHolder.get(world)}, which casts
 * the world straight to the holder interface - so the interface has to be mixed into the world
 * class itself rather than kept in a side map. Angelica does the same thing on {@code WorldClient}.
 * <p>
 * 1.8.9 funnels every client-side chunk load and unload through {@code doPreChunk}, which makes it
 * the single place both events can be observed.
 */
@Mixin(WorldClient.class)
public abstract class MixinWorldClientTracker implements ChunkTrackerHolder {

    @Unique
    private final ChunkTracker natron$tracker = new ChunkTrackerImpl();

    @Override
    public ChunkTracker sodium$getTracker() {
        return this.natron$tracker;
    }

    @Inject(method = "doPreChunk", at = @At("RETURN"))
    private void natron$trackChunk(int chunkX, int chunkZ, boolean loadChunk, CallbackInfo ci) {
        if (loadChunk) {
            // The client receives block and light data together, so a chunk is complete the moment
            // it arrives - there is no separate lighting stage to wait for as on the server.
            this.natron$tracker.onChunkStatusAdded(chunkX, chunkZ, ChunkStatus.FLAG_ALL);
        } else {
            this.natron$tracker.onChunkStatusRemoved(chunkX, chunkZ, ChunkStatus.FLAG_ALL);
        }
    }
}
