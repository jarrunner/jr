package pocapp.jr;

import org.teavm.interop.Address;

/**
 * Locate or auto-install a JDK of the given major version, matching jbang's own cache layout
 * (%USERPROFILE%\.jbang\cache\jdks\<majorVersion>) so the two tools can share downloads - mirrors
 * javainstall.c's autoInstallJava. See prp/09-prp-java_auto_install.md.
 *
 * If <cacheRoot>\<majorVersion>\bin\java.exe already exists, uses it directly with no network
 * access at all. Otherwise asks the user (unless assumeYes), then downloads the matching Eclipse
 * Temurin build from the Foojay Disco API (the same API jbang itself uses), verifies its SHA256,
 * extracts it with the tar.exe already bundled with Windows, and moves it into place.
 */
public final class JavaInstall {
    private JavaInstall() {}

    private static final int DEFAULT_VERSION = 21;

    /** Returns the installed JDK's home directory on success, or null on failure/decline - the
     *  caller should fall back to its existing "Java Not Found" error, same as javainstall.c. */
    public static String tryInstall(int majorVersion, boolean hasConsole, boolean guiMode, boolean assumeYes,
            String cacheRootOverride) {
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

        if (!assumeYes) {
            var prompt = "Java was not found.\n\nDownload and install Eclipse Temurin JDK " + version
                    + " (~200 MB) to:\n" + targetDir + "\n\nProceed?";
            if (!confirm(hasConsole, prompt)) {
                return null;
            }
        }

        // Step 1: resolve the package id for this major version (Windows x64 Temurin GA build)
        var apiUrl = "https://api.foojay.io/disco/v3.0/packages?distro=temurin&javafx_bundled=false"
                + "&libc_type=c_std_lib&directly_downloadable=true&archive_type=zip&operating_system=windows"
                + "&package_type=jdk&release_status=ga&architecture=x64&latest=available&version=" + version;
        var apiResponse = Http.getToBuffer(apiUrl, 16384);
        var pkgId = apiResponse == null ? null : Json.extractString(apiResponse, "\"id\":\"");
        if (pkgId == null) {
            Log.warn("auto-install: could not resolve package id (apiResponse=" + apiResponse + ")");
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

        var progress = new Progress(guiMode, hasConsole, "Downloading Eclipse Temurin JDK " + version + "...");
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

    private static String defaultCacheRoot() {
        var userProfile = Cstr.readEnv("USERPROFILE");
        if (userProfile == null || userProfile.isEmpty()) {
            return null;
        }
        return userProfile + "\\.jbang\\cache\\jdks";
    }

    private static String tempPath() {
        var buf = new byte[261];
        var addr = Address.ofData(buf);
        var len = WinApi.getTempPathA(buf.length, addr);
        if (len == 0 || len >= buf.length) {
            return "C:\\Windows\\Temp\\";
        }
        var sb = new StringBuilder();
        for (var i = 0; i < len; i++) {
            sb.append((char) (buf[i] & 0xFF));
        }
        return sb.toString();
    }

    private static boolean extractZip(String zipPath, String destDir) {
        var buf = new byte[261];
        var addr = Address.ofData(buf);
        var len = WinApi.getSystemDirectoryA(addr, buf.length);
        var sysDir = new StringBuilder();
        for (var i = 0; i < len; i++) {
            sysDir.append((char) (buf[i] & 0xFF));
        }
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
        return WinApi.messageBoxA(Address.fromInt(0), Cstr.of(message), Cstr.of("Java Not Found - Auto Install"),
                WinApi.MB_YESNO | WinApi.MB_ICONQUESTION) == WinApi.IDYES;
    }

    private static String readConsoleLine() {
        var handle = WinApi.getStdHandle(WinApi.STD_INPUT_HANDLE);
        var buf = new byte[256];
        var bufAddr = Address.ofData(buf);
        var readBuf = new byte[4];
        var readAddr = Address.ofData(readBuf);
        if (WinApi.readFile(handle, bufAddr, buf.length, readAddr, Address.fromInt(0)) == 0) {
            return null;
        }
        var n = readAddr.getInt();
        var sb = new StringBuilder();
        for (var i = 0; i < n; i++) {
            var c = (char) (buf[i] & 0xFF);
            if (c == '\r' || c == '\n') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
