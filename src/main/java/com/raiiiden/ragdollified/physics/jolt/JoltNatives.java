package com.raiiiden.ragdollified.physics.jolt;

import com.github.stephengold.joltjni.Jolt;
import com.raiiiden.ragdollified.Ragdollified;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

// Loads the Jolt native library once. Nothing in the jolt package may be touched until isReady(),
// since calling an unloaded jolt-jni crashes the JVM.
public final class JoltNatives {

    private JoltNatives() {
    }

    // Bumped with the jolt-jni dependency. The extraction directory is stamped with it so a mod
    // update never loads the previous release's .dll out of the cache.
    public static final String JOLT_JNI_VERSION = "6.0.0";

    // Prefixes the classpath layout the jolt-jni native jars publish, which is snaploader's:
    // <os>/<arch>/com/github/stephengold/<library>
    private static final String RESOURCE_ROOT = "com/github/stephengold/";

    private static volatile boolean attempted;
    private static volatile boolean ready;
    private static volatile String failureReason;

    public static boolean isReady() {
        return ready;
    }

    // Null while loading has not been attempted or has succeeded.
    public static String failureReason() {
        return failureReason;
    }

    // Idempotent. Returns false and records a reason rather than throwing: a physics engine that
    // will not load must degrade to "no ragdolls", never to a crashed game.
    public static synchronized boolean load() {
        if (attempted) return ready;
        attempted = true;
        try {
            Platform platform = Platform.detect();
            if (platform == null) {
                return fail("no Jolt native is bundled for os.name=" + System.getProperty("os.name")
                        + " os.arch=" + System.getProperty("os.arch"));
            }

            Path extracted = extract(platform);
            System.load(extracted.toAbsolutePath().toString());

            // Check classloaders first: if jolt-jni came from the library classpath, JNI natives can never bind.
            ClassLoader loaderOfLoadingClass = JoltNatives.class.getClassLoader();
            ClassLoader loaderOfJoltClasses = Jolt.class.getClassLoader();
            if (loaderOfLoadingClass != loaderOfJoltClasses) {
                return fail("jolt-jni classes were loaded by " + loaderOfJoltClasses
                        + " but the native library is bound to " + loaderOfLoadingClass
                        + "; JNI cannot bridge the two. The jolt-jni classes must be packaged into"
                        + " the mod itself (see the unpackJoltJniClasses task in build.gradle).");
            }

            Jolt.registerDefaultAllocator();
            // Route Jolt's own diagnostics into our log instead of stdout, so a native warning is
            // attributable in a user's crash report rather than an orphan line in latest.log.
            Jolt.installDefaultTraceCallback();
            if (!Jolt.newFactory()) {
                return fail("Jolt.newFactory() returned false");
            }
            Jolt.registerTypes();

            ready = true;
            Ragdollified.LOGGER.info("Jolt physics ready: {} ({})",
                    safeVersionString(), platform.resourcePath);
            return true;
        } catch (Throwable t) {
            // Throwable, not Exception: a missing or mismatched native surfaces as
            // UnsatisfiedLinkError or NoClassDefFoundError, and those must not escape either.
            Ragdollified.LOGGER.error("Failed to load Jolt natives; falling back to JBullet", t);
            return fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static String safeVersionString() {
        try {
            return Jolt.versionString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static boolean fail(String reason) {
        failureReason = reason;
        ready = false;
        Ragdollified.LOGGER.error("Jolt physics unavailable: {}", reason);
        return false;
    }

    // Extract the native beside the game directory, avoiding temp-dir antivirus, locks and cleaners.
    private static Path extract(Platform platform) throws IOException {
        Path directory = FMLPaths.GAMEDIR.get()
                .resolve(".ragdollified")
                .resolve("natives")
                .resolve(JOLT_JNI_VERSION);
        Files.createDirectories(directory);
        Path target = directory.resolve(platform.libraryFileName);

        try (InputStream in = JoltNatives.class.getClassLoader()
                .getResourceAsStream(platform.resourcePath)) {
            if (in == null) {
                throw new IOException("bundled native not found on the classpath: "
                        + platform.resourcePath);
            }
            long bundledSize = -1L;
            if (Files.exists(target)) {
                // Same version and same size means the previous extraction is intact; re-copying
                // would only risk failing against a file the OS still has mapped from this session.
                bundledSize = sizeOf(platform.resourcePath);
                if (bundledSize >= 0 && Files.size(target) == bundledSize) {
                    return target;
                }
            }
            Path temporary = directory.resolve(platform.libraryFileName + ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                in.transferTo(out);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                // Windows refuses to replace a DLL another process has loaded. If the existing file
                // is the right size it is already the one we want, so use it and drop the copy.
                Files.deleteIfExists(temporary);
                if (!Files.exists(target)) throw moveFailed;
            }
        }
        return target;
    }

    private static long sizeOf(String resourcePath) {
        try (InputStream in = JoltNatives.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) return -1L;
            long total = 0;
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) total += read;
            return total;
        } catch (IOException e) {
            return -1L;
        }
    }

    private record Platform(String resourcePath, String libraryFileName) {

        static Platform detect() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
            boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");

            if (os.contains("win")) {
                if (x64) return of("windows/x86-64/", "joltjni.dll");
                return null;
            }
            if (os.contains("mac") || os.contains("darwin")) {
                if (x64) return of("osx/x86-64/", "libjoltjni.dylib");
                if (arm64) return of("osx/aarch64/", "libjoltjni.dylib");
                return null;
            }
            if (os.contains("linux")) {
                if (x64) return of("linux/x86-64/", "libjoltjni.so");
                if (arm64) return of("linux/aarch64/", "libjoltjni.so");
                return null;
            }
            return null;
        }

        private static Platform of(String directory, String library) {
            return new Platform(directory + RESOURCE_ROOT + library, library);
        }
    }
}
