package kryptos.util;

import arc.files.Fi;
import arc.util.Log;
import arc.util.Time;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Installs a global uncaught-exception handler so that ANY crash (not just
 * ones in Kryptos code) gets written to a plain text file on device storage
 * before the process dies. This exists specifically so crashes can be
 * diagnosed on-device without needing adb/logcat access.
 *
 * File is written to Mindustry's own private/local files dir (app storage,
 * always writable, no permission needed), e.g. on Android:
 * /data/data/io.anuke.mindustry/files/kryptos-crash.txt
 *
 * Note: this only catches crashes that happen *inside the JVM* (uncaught
 * Java exceptions/errors, including OutOfMemoryError and
 * StackOverflowError). It cannot catch a native-level crash or the OS
 * force-killing the process directly (e.g. Android's low-memory killer
 * terminating the app without going through Java at all) -- if crashes
 * persist even with this installed and the file is never written, that
 * points at an OS-level kill rather than a Java exception.
 */
public final class KryptosCrashLogger {

    private static final String FILE_NAME = "kryptos-crash.txt";
    private static boolean installed = false;

    private KryptosCrashLogger() {}

    public static void install() {
        if (installed) return;
        installed = true;

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashFile(thread, throwable);
            } catch (Throwable writeFailure) {
                // If we can't even write the crash file (e.g. genuinely out of
                // memory, or a storage permission issue), log BOTH the write
                // failure and the original crash -- otherwise the original
                // exception that actually crashed the game is silently lost,
                // and only this secondary write error ever surfaces.
                Log.err("[Kryptos] Failed to write crash file", writeFailure);
                Log.err("[Kryptos] Original crash was:", throwable);
            }

            // Preserve default behavior (Mindustry's own handler, or the
            // system default) so existing crash-dialog behavior is unaffected.
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });

        Log.info("[Kryptos] Crash logger installed.");
    }

    private static void writeCrashFile(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));

        String content = "=== Kryptos crash report ===\n" +
            "Time: " + Time.millis() + "\n" +
            "Thread: " + thread.getName() + "\n\n" +
            sw.toString();

        Fi file = localFile();
        file.writeString(content, false);

        Log.err("[Kryptos] CRASH written to @", file.absolutePath());
    }

    private static Fi localFile() {
        // arc.Core.files.local(...) points at the app's own private files
        // dir -- always writable with no storage permission required,
        // unlike files.external(...), which maps to the *root* of shared
        // external storage on Android and is blocked by scoped storage
        // (EPERM) on Android 10+ without MANAGE_EXTERNAL_STORAGE.
        return arc.Core.files.local(FILE_NAME);
    }
}
