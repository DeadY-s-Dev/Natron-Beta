package me.natron.mixin.celeritas;

import me.natron.NatronDiagnosticLog;
import me.natron.render.NatronCeleritasAvailability;
import me.natron.render.NatronCeleritasSetup;
import me.natron.render.NatronFogService;
import me.natron.render.NatronGlStateGuard;
import me.natron.render.NatronRenderPassConfiguration;
import me.natron.render.NatronWorldRenderer;
import me.natron.render.ViewportBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumWorldBlockLayer;
import org.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Takes vanilla's terrain pipeline out of the loop and routes it to Celeritas.
 * <p>
 * Modelled on Nothirium's 1.8.9 port, which cancels the same set of {@link RenderGlobal} methods at
 * HEAD. Each of these owns a piece of the vanilla chunk renderer that Celeritas now replaces:
 * building the visible set, uploading meshes, marking chunks dirty and drawing a layer.
 * <p>
 * {@code setupTerrain} is cancelled at the first profiler call rather than at HEAD, because the
 * lines above it react to a render distance change - letting them run keeps that working.
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobalCeleritas {

    @Unique
    private final ViewportBuilder natron$viewports = new ViewportBuilder();

    @Unique
    private int natron$frame;

    @Inject(method = "setWorldAndLoadRenderers", at = @At("RETURN"))
    private void natron$setWorld(WorldClient world, CallbackInfo ci) {
        // Startup diagnostics are queued rather than written as they happen, because the probe that
        // produces most of them runs during FML pre-init - too early to trust
        // Minecraft.mcDataDir for a file path. Loading a world is late enough for that to be
        // settled, and is the first moment anything here could have gone wrong in a way worth
        // reading about.
        NatronDiagnosticLog.flushToFile();

        // The one gate: if this GPU cannot run Celeritas, never construct the renderer that would
        // need it. Every other injection in this mixin already checks getInstanceOrNull() != null
        // and no-ops when it is null, so leaving the instance unset here is enough on its own -
        // vanilla's terrain renderer runs completely untouched.
        if (!NatronCeleritasAvailability.isSupported()) {
            NatronCeleritasAvailability.warnOnceIfUnsupported();
            NatronDiagnosticLog.flushToFile();
            return;
        }

        // Has to happen before the first managed block: entering one runs VANILLA_STATE_RESETTER,
        // which Celeritas ships as a stub that throws until the host mod replaces it.
        NatronCeleritasSetup.init();

        // Celeritas refuses any GL work outside a managed block ("Tried to access device from
        // unmanaged context"), and setting the world allocates the region arenas.
        try (NatronGlStateGuard guard = NatronGlStateGuard.open()) {
            NatronWorldRenderer.getInstance().setWorld(world);
        }
    }

    @Inject(method = "loadRenderers", at = @At("RETURN"))
    private void natron$reload(CallbackInfo ci) {
        final NatronWorldRenderer renderer = NatronWorldRenderer.getInstanceOrNull();
        if (renderer == null) {
            return;
        }

        try (NatronGlStateGuard guard = NatronGlStateGuard.open()) {
            renderer.reload();
        }
    }

    @Inject(
        method = "setupTerrain",
        cancellable = true,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/profiler/Profiler;startSection(Ljava/lang/String;)V",
            ordinal = 0))
    private void natron$setupTerrain(Entity viewEntity, double partialTicks, ICamera camera,
                                        int frameCount, boolean spectator, CallbackInfo ci) {
        final NatronWorldRenderer renderer = NatronWorldRenderer.getInstanceOrNull();
        if (renderer == null) {
            return;
        }

        // Single GL matrix read for the whole frame - createChunkRenderMatrices() reuses these
        // same buffers for every layer draw that follows instead of querying GL again.
        renderer.refreshCameraMatrices();

        // Same idea for the alpha-test baseline: CUTOUT_MIPPED_PASS alone gets begin()/end()'d
        // twice a frame (both the CUTOUT and CUTOUT_MIPPED layers route to it), and each of those
        // used to re-query GL_ALPHA_TEST_FUNC/REF to know what to restore on exit.
        NatronRenderPassConfiguration.refreshAlphaBaseline();

        // And again for fog: Celeritas's own ChunkShaderFogComponent queries this once per pass
        // just to build its shader cache key, then again inside setup() - up to five driver calls
        // per pass, times up to four passes a frame, for state that is fixed for the whole frame.
        NatronFogService.refresh();

        final double x = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
        final double y = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks;
        final double z = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;

        final Viewport viewport = this.natron$viewports.create(
            renderer.getProjectionBuffer(), renderer.getModelViewBuffer(), x, y, z);
        final SimpleWorldRenderer.CameraState cameraState = new SimpleWorldRenderer.CameraState(
            x, y, z, viewEntity.rotationPitch, viewEntity.rotationYaw,
            Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16.0F);

        try (NatronGlStateGuard guard = NatronGlStateGuard.open()) {
            renderer.setupTerrain(viewport, cameraState, this.natron$frame++, spectator, false);
        }

        ci.cancel();
    }

    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/EnumWorldBlockLayer;DILnet/minecraft/entity/Entity;)I",
        cancellable = true, at = @At("HEAD"))
    private void natron$renderLayer(EnumWorldBlockLayer layer, double partialTicks, int pass,
                                       Entity entity, CallbackInfoReturnable<Integer> cir) {
        final NatronWorldRenderer renderer = NatronWorldRenderer.getInstanceOrNull();
        if (renderer == null) {
            return;
        }

        final double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        final double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        final double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;

        // Vanilla renders entities between the CUTOUT and TRANSLUCENT layer calls (see
        // EntityRenderer.renderWorldPass), so whatever GL state this leaves behind is exactly
        // what entity rendering inherits. The guard is what keeps that state sane.
        try (NatronGlStateGuard guard = NatronGlStateGuard.open()) {
            // configureShaderInterface() only tells the shader which texture UNIT each sampler
            // reads from (block = 0, light = 1); it never binds an actual texture there. Nothing
            // else in this pipeline binds the block atlas either, so unit 0 was whatever vanilla
            // happened to leave bound - correct most of the time by luck, wrong whenever
            // something else (the held item, a GUI element) touched unit 0 first. Bind it for
            // real (the guard has already forced unit 0 active). Unit 1 is left alone: vanilla's
            // own EntityRenderer.updateLightmap() binds the lightmap there once a frame, before
            // world rendering starts, and this guard's glPopAttrib restores it faithfully either
            // way.
            Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);

            renderer.drawChunkLayer(layer, x, y, z);
        }

        cir.setReturnValue(Integer.valueOf(0));
    }

    /** Celeritas pumps its own build queue from setupTerrain. */
    @Inject(method = "updateChunks", cancellable = true, at = @At("HEAD"))
    private void natron$updateChunks(long finishTimeNano, CallbackInfo ci) {
        if (NatronWorldRenderer.getInstanceOrNull() != null) {
            ci.cancel();
        }
    }

    @Inject(method = "stopChunkUpdates", cancellable = true, at = @At("HEAD"))
    private void natron$stopChunkUpdates(CallbackInfo ci) {
        if (NatronWorldRenderer.getInstanceOrNull() != null) {
            ci.cancel();
        }
    }

    @Inject(method = "markBlocksForUpdate", cancellable = true, at = @At("HEAD"))
    private void natron$markBlocksForUpdate(int x1, int y1, int z1, int x2, int y2, int z2,
                                               CallbackInfo ci) {
        final NatronWorldRenderer renderer = NatronWorldRenderer.getInstanceOrNull();
        if (renderer == null) {
            return;
        }

        renderer.invalidateAndScheduleRebuild(x1, y1, z1, x2, y2, z2);
        ci.cancel();
    }

    @Inject(method = "getDebugInfoRenders", cancellable = true, at = @At("HEAD"))
    private void natron$debugInfo(CallbackInfoReturnable<String> cir) {
        final NatronWorldRenderer renderer = NatronWorldRenderer.getInstanceOrNull();
        if (renderer != null) {
            cir.setReturnValue(renderer.getChunksDebugString());
        }
    }
}
