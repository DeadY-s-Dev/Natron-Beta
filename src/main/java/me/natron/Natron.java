package me.natron;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mod entry point.
 * <p>
 * There is deliberately very little here. This started out carrying its own set of tweaks to
 * vanilla's chunk renderer - more build threads, a fixed update budget, reused terrain collections
 * - each gated behind a config option. Every one of those became dead weight the moment the
 * Celeritas bridge landed: it cancels {@code RenderGlobal.setupTerrain},
 * {@code RenderGlobal.updateChunks} and {@code RenderGlobal.stopChunkUpdates} outright, so vanilla's
 * chunk pipeline never runs to be optimised. The worst of them was still starting up to five daemon
 * threads for a {@code ChunkRenderDispatcher} that no longer receives any work.
 * <p>
 * What is left is the renderer itself, which lives in the {@code celeritas} source set.
 */
@Mod(modid = Natron.MODID, name = Natron.NAME, version = Natron.VERSION, clientSideOnly = true)
public class Natron {

    public static final String MODID = "natron";
    public static final String NAME = "Natron";
    public static final String VERSION = "1.0.0";

    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        me.natron.celeritas.CeleritasProbe.probe();
    }
}
