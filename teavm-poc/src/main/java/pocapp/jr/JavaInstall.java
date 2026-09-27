package pocapp.jr;

import static pocapp.jr.N.*;

/**
 * Locate or auto-install a JDK of the given major version, matching jbang's own cache layout
 * (%USERPROFILE%\.jbang\cache\jdks\<majorVersion>) so the two tools can share downloads - mirrors
 * javainstall.c's autoInstallJava. See prp/09-prp-java_auto_install.md.
 *
 * If <cacheRoot>\<majorVersion>\bin\java.exe already exists, uses it directly with no network
 * access at all. Otherwise asks the user (unless assumeYes), then downloads the matching Eclipse
 * Temurin build from the Foojay Disco API (the same API jbang itself uses) for the machine's native
 * architecture (on ARM64, Azul Zulu where Temurin has no ARM64 build - see NativeArch), verifies its SHA256,
 * extracts it with the tar.exe already bundled with Windows, and moves it into place.
 */
public final class JavaInstall {
    private JavaInstall() {}

    private static final int DEFAULT_VERSION = 25; // same as javainstall.c JI_DEFAULT_VERSION

    // Which build to fetch, in order, as {distro, architecture, vendor} triples - same lists as javainstall.c.
    // Temurin first everywhere. On ARM64 Temurin publishes a Windows zip for some versions only (21 but not
    // 17 or 25, as of 2026-09), so Azul Zulu comes next - the one ARM64 build on Foojay with a SHA-256
    // checksum, which Step 4 insists on - then Temurin x64 under emulation.
    private static final String[] BUILDS_X64 = {"temurin", "x64", "Eclipse Temurin"};
    private static final String[] BUILDS_ARM64 = {
        "temurin", "aarch64", "Eclipse Temurin",
        "zulu", "aarch64", "Azul Zulu",
        "temurin", "x64", "Eclipse Temurin (x64, emulated)",
    };

    /** Returns the installed JDK's home directory on success, or null on failure/decline - the
     *  caller should fall back to its existing "Java Not Found" error, same as javainstall.c.
     *  atLeast/reason mirror autoInstallJava's atLeast/reason: atLeast widens the direct-cache-hit
     *  check to "the newest cached JDK &gt;= majorVersion" (java.version=NN+); reason, when non-null,
     *  is the "wrong version found" message shown ahead of the install prompt instead of the
     *  generic "Java was not found." */
    public static String tryInstall(int majorVersion, boolean atLeast, String reason, boolean hasConsole,
            boolean guiMode, boolean assumeYes, String cacheRootOverride) {
        var version = majorVersion > 0 ? majorVersion : DEFAULT_VERSION;

        var cacheRoot = cacheRootOverride != null && !cacheRootOverride.isEmpty()
                ? cacheRootOverride : defaultCacheRoot();
        if (cacheRoot == null) {
            return null;
        }

        var targetDir = cacheRoot + "\\" + version;
        if (FileIo.exists(targetDir + "\\bin\\java.exe")) {
            return targetDir;
        }

        if (atLeast) {
            var cached = findCachedAtLeast(cacheRoot, version);
            if (cached != null) {
                return cached;
            }
        }

        var arm64 = NativeArch.foojayName().equals("aarch64");
        if (!assumeYes) {
            var what = arm64
                    ? "JDK " + version + " for ARM64 (Eclipse Temurin, or Azul Zulu where Temurin has no ARM64 build; ~200 MB)"
                    : "Eclipse Temurin JDK " + version + " (~200 MB)";
            var prompt = (reason != null ? reason : "Java was not found.")
                    + "\n\nDownload and install " + what + " to:\n" + targetDir + "\n\nProceed?";
            if (!confirm(hasConsole, prompt)) {
                return null;
            }
        }

        // Step 1: resolve the package id for this major version - the first build in the list Foojay has
        var builds = arm64 ? BUILDS_ARM64 : BUILDS_X64;
        String pkgId = null;
        String vendor = null;
        for (var i = 0; i < builds.length && pkgId == null; i += 3) {
            var apiUrl = "https://api.foojay.io/disco/v3.0/packages?distro=" + builds[i] + "&javafx_bundled=false"
                    + "&libc_type=c_std_lib&directly_downloadable=true&archive_type=zip&operating_system=windows"
                    + "&package_type=jdk&release_status=ga&architecture=" + builds[i + 1]
                    + "&latest=available&version=" + version;
            var apiResponse = Http.getToBuffer(apiUrl, 16384);
            if (apiResponse == null) {
                Log.warn("auto-install: package query failed (url=" + apiUrl + ")");
                return null;
            }
            pkgId = Json.extractString(apiResponse, "\"id\":\"");
            vendor = builds[i + 2];
        }
        if (pkgId == null) {
            Log.warn("auto-install: no package found for JDK " + version);
            return null;
        }

        // Step 2: resolve the direct download URL + expected checksum for that package
        var idResponse = Http.getToBuffer("https://api.foojay.io/disco/v3.0/ids/" + pkgId, 8192);
        if (idResponse == null) {
            Log.warn("auto-install: ids/" + pkgId + " query failed");
            return null;
        }
        var downloadUrl = Json.extractString(idResponse, "\"direct_download_uri\":\"");
        var expectedChecksum = Json.extractString(idResponse, "\"checksum\":\"");
        var filename = Json.extractString(idResponse, "\"filename\":\"");
        if (downloadUrl == null || expectedChecksum == null || filename == null) {
            Log.warn("auto-install: unexpected ids/ response (idResponse=" + idResponse + ")");
            return null;
        }

        // Step 3: download with progress, into a scratch temp dir
        var tempDir = tempPath() + "jr-jdk-install";
        Dirs.mkdirRecursive(tempDir);
        var zipPath = tempDir + "\\" + filename;

        var progress = new Progress(guiMode, hasConsole, "Downloading " + vendor + " JDK " + version + "...");
        var downloaded = Http.downloadToFile(downloadUrl, zipPath, progress);
        progress.finish();
        if (!downloaded) {
            Log.warn("auto-install: download failed (url=" + downloadUrl + ")");
            Dirs.removeDirTree(tempDir);
            return null;
        }

        // Step 4: verify integrity before extracting anything
        var actualChecksum = Sha256.ofFile(zipPath);
        if (actualChecksum == null || !AsciiStr.equalsIgnoreCase(actualChecksum, expectedChecksum)) {
            Log.warn("auto-install: checksum mismatch (expected=" + expectedChecksum + " actual=" + actualChecksum
                    + ")");
            Dirs.removeDirTree(tempDir);
            return null;
        }

        // Step 5: extract, then find the single top-level folder the zip contains
        var extractDir = tempDir + "\\extract";
        Dirs.removeDirTree(extractDir);
        Dirs.mkdirRecursive(extractDir);
        if (!extractZip(zipPath, extractDir)) {
            Log.warn("auto-install: tar.exe extraction failed (zip=" + zipPath + ")");
            Dirs.removeDirTree(tempDir);
            return null;
        }
        var subdir = Dirs.findSingleSubdir(extractDir);
        if (subdir == null) {
            Log.warn("auto-install: no top-level folder found under " + extractDir);
            Dirs.removeDirTree(tempDir);
            return null;
        }

        // Step 6: promote it into the jbang-compatible flattened cache slot
        Dirs.mkdirRecursive(cacheRoot);
        if (!Dirs.moveDirectory(extractDir + "\\" + subdir, targetDir)) {
            Log.warn("auto-install: move " + extractDir + "\\" + subdir + " -> " + targetDir + " failed");
            Dirs.removeDirTree(tempDir);
            return null;
        }

        Dirs.removeDirTree(tempDir);
        return targetDir;
    }

    /** For "NN+": the newest &lt;cacheRoot&gt;\&lt;N&gt; with N &gt;= minVersion and a bin\java.exe. Only
     *  all-digit folder names count, which is how jbang names its own - mirrors javainstall.c's
     *  jiFindCachedAtLeast. */
    private static String findCachedAtLeast(String cacheRoot, int minVersion) {
        var best = 0;
        for (var name : Dirs.listDirNames(cacheRoot)) {
            if (!isAllDigits(name)) {
                continue;
            }
            var n = Atoi.parse(name);
            if (n < minVersion || n <= best) {
                continue;
            }
            if (FileIo.exists(cacheRoot + "\\" + name + "\\bin\\java.exe")) {
                best = n;
            }
        }
        return best == 0 ? null : cacheRoot + "\\" + best;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (var i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String defaultCacheRoot() {
        var userProfile = Cstr.readEnv("USERPROFILE");
        if (userProfile == null || userProfile.isEmpty()) {
            return null;
        }
        return userProfile + "\\.jbang\\cache\\jdks";
    }

    private static String tempPath() {
        var buf = alloc(261);
        var len = WinApi.getTempPathA(261, buf);
        return len == 0 || len >= 261 ? "C:\\Windows\\Temp\\" : string(buf, len);
    }

    private static boolean extractZip(String zipPath, String destDir) {
        var buf = alloc(261);
        var sysDir = string(buf, WinApi.getSystemDirectoryA(buf, 261));
        var cmd = "\"" + sysDir + "\\tar.exe\" -xf \"" + zipPath + "\" -C \"" + destDir + "\"";
        return ProcessLauncher.runHiddenAndWait(cmd);
    }

    private static boolean confirm(boolean hasConsole, String message) {
        if (hasConsole) {
            System.out.println();
            System.out.println(message);
            System.out.print("[Y/n] ");
            var line = readConsoleLine();
            return line == null || line.isEmpty() || line.charAt(0) == 'y' || line.charAt(0) == 'Y';
        }
        return WinApi.messageBoxA(NULL, cstr(message), cstr("Java Not Found - Auto Install"),
                WinApi.MB_YESNO | WinApi.MB_ICONQUESTION) == WinApi.IDYES;
    }

    private static String readConsoleLine() {
        var handle = WinApi.getStdHandle(WinApi.STD_INPUT_HANDLE);
        var buf = alloc(256);
        var read = intVar();
        if (WinApi.readFile(handle, buf, 256, read, NULL) == 0) {
            return null;
        }
        var line = string(buf, read.getInt());
        var eol = 0;
        while (eol < line.length() && line.charAt(eol) != '\r' && line.charAt(eol) != '\n') eol++;
        return line.substring(0, eol);
    }
}
