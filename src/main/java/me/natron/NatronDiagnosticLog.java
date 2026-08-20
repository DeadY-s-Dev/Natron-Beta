package me.natron;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.minecraft.client.Minecraft;

/**
 * A second, guaranteed copy of every diagnostic line, written straight to a file rather than
 * through Log4j.
 * <p>
 * Every diagnostic line this mod prints - {@code CeleritasProbe}'s capability dump, the
 * availability gate's warning - went through {@code Natron.LOGGER.info(...)} alone, and testing
 * kept coming back with those lines simply missing. Nothing in this codebase decides whether an
 * {@code info} call is visible; whatever sits between it and a line on screen does - a launcher's
 * own log viewer, a stricter configured level, a console the player was not looking at. Log4j is
 * not this mod's to configure, especially inside a client such as Lunar that bundles its own.
 * <p>
 * So this does not try to fix that pipe. It adds one that bypasses it: a plain file, in the game
 * directory, written directly. {@code LOGGER.info} is still called alongside it - free when it
 * works - but the file is what a test session can be asked to attach, because it does not depend
 * on guessing which log the player's setup actually surfaces.
 * <p>
 * <b>Nothing here is on a hot path.</b> The callers are the startup probe and the availability
 * gate, both of which run once. That is why this buffers and writes in one go at the end rather
 * than holding a {@link PrintWriter} open: an open file handle that exists purely to serve a
 * handful of startup lines is weight with nothing to show for it. It used to stay open for the
 * whole session, back when a per-frame profiler was also writing through it; that profiler is
 * gone, and this followed it.
 */
public final class NatronDiagnosticLog {

    private static final String FILE_NAME = "natron-diagnostics.log";

    private static final SimpleDateFormat TIMESTAMP = new SimpleDateFormat("HH:mm:ss");

    /**
     * Lines waiting to be written.
     * <p>
     * Buffered rather than written immediately because the earliest callers run during FML
     * pre-init, before {@code Minecraft.getMinecraft().mcDataDir} is dependable - there is no game
     * directory to resolve yet, and asking for one that early is how a diagnostic meant to explain
     * a failure becomes a second failure. Holding the lines costs a few strings and lets
     * {@link #flushToFile()} pick the moment.
     */
    private static final List<String> pending = new ArrayList<String>();

    /** True once writing has failed, so a broken path is not retried on every call. */
    private static boolean disabled;

    private NatronDiagnosticLog() {
    }

    /** Logs through Log4j, and queues the same line for the diagnostic file. */
    public static synchronized void write(String message) {
        Natron.LOGGER.info(message);
        pending.add("[" + TIMESTAMP.format(new Date()) + "] " + message);
    }

    /** Same, but for a failure - the stack trace is queued too, not just the message. */
    public static synchronized void writeError(String message, Throwable t) {
        Natron.LOGGER.error(message, t);

        write(message);
        pending.add("    " + t.toString());

        for (StackTraceElement frame : t.getStackTrace()) {
            pending.add("        at " + frame);
        }
    }

    /**
     * Writes everything queued so far and closes the file. Safe to call more than once; each call
     * appends only what has been queued since the last one.
     */
    public static synchronized void flushToFile() {
        if (disabled || pending.isEmpty()) {
            return;
        }

        PrintWriter writer = null;

        try {
            final File gameDir = Minecraft.getMinecraft().mcDataDir;
            final File file = new File(gameDir, FILE_NAME);

            // Append: a session that joins several worlds should not lose the earlier ones' lines
            // to the last one's write.
            writer = new PrintWriter(new FileWriter(file, true));
            writer.println();
            writer.println("=== natron session started " + new Date() + " ===");

            for (String line : pending) {
                writer.println(line);
            }

            Natron.LOGGER.info("[celeritas] diagnostic log file: {}", file.getAbsolutePath());
        } catch (Throwable t) {
            disabled = true;
            Natron.LOGGER.error("[celeritas] could not write diagnostic log file", t);
        } finally {
            if (writer != null) {
                writer.close();
            }
            pending.clear();
        }
    }
}
