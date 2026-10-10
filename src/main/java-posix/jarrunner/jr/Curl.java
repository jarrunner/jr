package jarrunner.jr;

import java.util.ArrayList;

/** Every download on POSIX runs curl (in /usr/bin on macOS and on every common Linux): the jar (RemoteJar), the
 *  update file (UpdateCheck) and the update itself (SelfUpdate). curl follows GitHub's redirect to its CDN, turns an
 *  HTTP error into a failure rather than a saved error page, and shows its own progress bar on a terminal. */
final class Curl {
    private Curl() {}

    /** Downloads url to file. With resume, a .part left by a dropped connection is continued (-C -), and a server that
     *  refuses to resume (curl exit 33) gets one fresh start; without it, the file is written from scratch. */
    static boolean download(String url, String file, boolean resume) {
        var args = new ArrayList<String>();
        args.add("-fL");
        args.add(PosixApi.isatty(2) != 0 ? "-#" : "-sS");
        args.add("--retry");
        args.add("2");
        if (resume) {
            args.add("-C");
            args.add("-");
        } else {
            PosixApi.unlink(file);
        }
        args.add("-o");
        args.add(file);
        args.add(url);
        var curl = path();
        var r = ProcessLauncher.launch(curl, args);
        if (resume && r.started && r.exitCode == 33) {
            PosixApi.unlink(file);
            r = ProcessLauncher.launch(curl, args);
        }
        if (!r.started) Log.error("could not start " + curl);
        return r.started && r.exitCode == 0;
    }

    /** A small text file (at most maxBytes), fetched quietly into file and read back; null if it could not be fetched.
     *  curl creates file's folder. */
    static String text(String url, String file, int maxBytes) {
        var args = new ArrayList<String>();
        args.add("-fsSL");
        args.add("--retry");
        args.add("2");
        args.add("--max-filesize");
        args.add("" + maxBytes);
        args.add("--create-dirs");
        args.add("-o");
        args.add(file);
        args.add(url);
        PosixApi.unlink(file);
        var r = ProcessLauncher.launch(path(), args);
        return r.started && r.exitCode == 0 ? FileIo.readAll(file) : null;
    }

    private static String path() {
        var curl = JavaFinder.findInPath("curl");
        return curl == null || curl.isEmpty() ? "/usr/bin/curl" : curl;
    }
}
