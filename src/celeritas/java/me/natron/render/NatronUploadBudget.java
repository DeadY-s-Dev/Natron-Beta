package me.natron.render;

/**
 * How many finished section meshes one {@code uploadChunks} call may upload.
 * <p>
 * <b>Not part of Angelica.</b> Angelica overrides {@code uploadChunks} only to wrap the super call
 * in a profiler zone, and neither it nor Celeritas bounds the drain - {@code collectChunkBuildResults}
 * takes every result that finished since the last frame and {@code uploadChunks} pushes all of them
 * to the GPU before returning. That is the right call for Angelica, whose mesher writes straight
 * into Celeritas's vertex format, so its workers finish small and often.
 * <p>
 * This port's mesher is heavier per section - vanilla's block renderer into a vanilla buffer, then a
 * transcode of that whole buffer - so results arrive in clumps rather than a trickle, and a frame
 * that happens to collect a clump stalls. That shows up as the frame time swinging rather than as a
 * lower average, which is exactly what testing reported after this cap was removed for being absent
 * from Angelica. Removing it was the right instinct applied to the wrong file: the parts of this
 * port that deviate from Angelica because the mesher differs are the parts that have to.
 * <p>
 * The cap is not a fixed count. Undrained results stay queued holding their {@code NativeBuffer}s -
 * off-heap memory released only by the {@code output().delete()} loop the capped-off results never
 * reach that frame - so a fixed cap against an unbounded producer trades frame spikes for unbounded
 * memory. Scaling it with the backlog keeps both bounded: bursts spread across frames, but a backlog
 * that keeps growing raises the cap until it drains.
 */
public final class NatronUploadBudget {

    /** Per-call ceiling when an upload is a write into persistently mapped memory, so nearly free. */
    private static final int CAP_MAPPED = 192;

    /** Per-call ceiling on the fallback path, where each section is an upload plus a copy. */
    private static final int CAP_FALLBACK = 48;

    /**
     * How much backlog may sit undrained. The cap rises to {@code backlog / 4} regardless of the
     * measurement above, so the queue cannot outrun the drain. A safety valve, not a tuning knob.
     */
    private static final int BACKLOG_DIVISOR = 4;

    private NatronUploadBudget() {
    }

    /**
     * @param backlog results waiting in the queue as this drain begins
     * @return how many results this drain may take
     */
    public static int capFor(int backlog) {
        final int base = NatronGlCaps.hasPersistentMapping() ? CAP_MAPPED : CAP_FALLBACK;

        // Never below the base, so a small backlog clears outright; above it the cap tracks the
        // backlog, so the queue cannot grow without the drain rate growing with it.
        return Math.max(base, backlog / BACKLOG_DIVISOR);
    }
}
