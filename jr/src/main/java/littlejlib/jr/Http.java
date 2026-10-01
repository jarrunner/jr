package littlejlib.jr;

import org.teavm.interop.Address;

import static littlejlib.jr.N.*;

/** Minimal WinHTTP GET client - just enough for the two small Foojay Disco API JSON calls and
 *  the one big JDK zip download JavaInstall needs. No URL-cracking struct (WinHttpCrackUrl) is
 *  used - every URL here is plain https://host/path, so a hand string-split is enough and avoids
 *  marshaling yet another WinAPI struct for no real benefit. The two methods below deliberately
 *  don't share a common "open the request" helper: bundling the session/connect/request Address
 *  handles into a returned holder object is untested territory in this port (see the Address[]
 *  array bug in guidelines.teavmcpp.md) - each stays a flat sequence of local variables instead,
 *  matching JliLauncher's own style, at the cost of a little duplication. */
public final class Http {
    private Http() {}

    private static final int READ_CHUNK = 65536;

    public static String getToBuffer(String url, int maxBytes) {
        var host = hostOf(url);
        var path = pathOf(url);
        var https = url.startsWith("https://");

        return memScoped(() -> {
            var session = WinApi.winHttpOpen(wcstr("jr-launcher/1.0"), WinApi.WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                    NULL, NULL, 0);
            if (session.toLong() == 0) {
                return null;
            }
            var connect = WinApi.winHttpConnect(session, wcstr(host), (short) (https ? 443 : 80), 0);
            if (connect.toLong() == 0) {
                WinApi.winHttpCloseHandle(session);
                return null;
            }
            var request = WinApi.winHttpOpenRequest(connect, wcstr("GET"), wcstr(path), NULL,
                    NULL, NULL, https ? WinApi.WINHTTP_FLAG_SECURE : 0);
            if (request.toLong() == 0 || !sendAndReceive(request)) {
                WinApi.winHttpCloseHandle(connect);
                WinApi.winHttpCloseHandle(session);
                return null;
            }

            var sb = new StringBuilder();
            var buf = alloc(READ_CHUNK);
            var available = intVar();
            var read = intVar();
            while (sb.length() < maxBytes) {
                if (WinApi.winHttpQueryDataAvailable(request, available) == 0 || available.getInt() == 0) {
                    break;
                }
                var toRead = Math.min(available.getInt(), READ_CHUNK);
                if (WinApi.winHttpReadData(request, buf, toRead, read) == 0 || read.getInt() == 0) {
                    break;
                }
                for (var i = 0; i < read.getInt(); i++) {
                    sb.append((char) (buf.add(i).getByte() & 0xFF));
                }
            }

            WinApi.winHttpCloseHandle(request);
            WinApi.winHttpCloseHandle(connect);
            WinApi.winHttpCloseHandle(session);
            return sb.toString();
        });
    }

    public static boolean downloadToFile(String url, String outPath, Progress progress) {
        return downloadToFile(url, outPath, progress, false);
    }

    /** With resume, an existing outPath is continued with a Range request: a 206 appends to it, a
     *  200 (the server ignored the range) starts over, anything else fails and leaves the partial
     *  file for the next attempt. Only 200/206 count as success, so an error page is never written
     *  as if it were the file. The caller verifies the finished file (a hash), which also catches
     *  a partial file that no longer matches what the server now holds. */
    public static boolean downloadToFile(String url, String outPath, Progress progress, boolean resume) {
        var host = hostOf(url);
        var path = pathOf(url);
        var https = url.startsWith("https://");
        var existingSize = resume ? FileInfo.size(outPath) : -1;
        var existing = existingSize > 0 ? existingSize : 0L;

        return memScoped(() -> {
            var session = WinApi.winHttpOpen(wcstr("jr-launcher/1.0"), WinApi.WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                    NULL, NULL, 0);
            if (session.toLong() == 0) {
                return false;
            }
            var connect = WinApi.winHttpConnect(session, wcstr(host), (short) (https ? 443 : 80), 0);
            if (connect.toLong() == 0) {
                WinApi.winHttpCloseHandle(session);
                return false;
            }
            var request = WinApi.winHttpOpenRequest(connect, wcstr("GET"), wcstr(path), NULL,
                    NULL, NULL, https ? WinApi.WINHTTP_FLAG_SECURE : 0);
            var range = existing > 0 ? "Range: bytes=" + existing + "-\r\n" : null;
            if (request.toLong() == 0 || !sendAndReceive(request, range)) {
                WinApi.winHttpCloseHandle(connect);
                WinApi.winHttpCloseHandle(session);
                return false;
            }

            var status = queryNumber(request, WinApi.WINHTTP_QUERY_STATUS_CODE);
            var appending = existing > 0 && status == 206;
            if (status != 200 && !appending) {
                Log.warn("download: HTTP " + status + " for " + url);
                WinApi.winHttpCloseHandle(request);
                WinApi.winHttpCloseHandle(connect);
                WinApi.winHttpCloseHandle(session);
                return false;
            }
            var offset = appending ? existing : 0L;
            if (appending) {
                Log.info("download: resuming at byte " + existing);
            }
            var remaining = queryNumber(request, WinApi.WINHTTP_QUERY_CONTENT_LENGTH);
            var totalBytes = remaining > 0 ? offset + remaining : 0L;

            var out = WinApi.fopen(cstr(outPath), cstr(appending ? "ab" : "wb"));
            if (out.toLong() == 0) {
                WinApi.winHttpCloseHandle(request);
                WinApi.winHttpCloseHandle(connect);
                WinApi.winHttpCloseHandle(session);
                return false;
            }

            var buf = alloc(READ_CHUNK);
            var available = intVar();
            var read = intVar();
            var totalRead = offset;
            var ok = true;
            while (true) {
                if (WinApi.winHttpQueryDataAvailable(request, available) == 0 || available.getInt() == 0) {
                    break;
                }
                var toRead = Math.min(available.getInt(), READ_CHUNK);
                if (WinApi.winHttpReadData(request, buf, toRead, read) == 0) {
                    ok = false;
                    break;
                }
                var got = read.getInt();
                if (got == 0) {
                    break;
                }
                WinApi.fwrite(buf, 1, got, out);
                totalRead += got;
                if (progress != null) {
                    progress.update(totalRead, totalBytes);
                }
            }

            WinApi.fclose(out);
            WinApi.winHttpCloseHandle(request);
            WinApi.winHttpCloseHandle(connect);
            WinApi.winHttpCloseHandle(session);
            return ok;
        });
    }

    private static boolean sendAndReceive(Address request) {
        return sendAndReceive(request, null);
    }

    /** headers: extra request headers ("Name: value\r\n"), or null. -1 as the length tells WinHTTP
     *  the string is NUL-terminated. */
    private static boolean sendAndReceive(Address request, String headers) {
        var h = headers == null ? NULL : wcstr(headers);
        return WinApi.winHttpSendRequest(request, h, headers == null ? 0 : -1, NULL, 0, 0, 0L) != 0
                && WinApi.winHttpReceiveResponse(request, NULL) != 0;
    }

    private static long queryNumber(Address request, int header) {
        var value = intVar();
        var size = intVar();
        size.putInt(4);
        if (WinApi.winHttpQueryHeaders(request, header | WinApi.WINHTTP_QUERY_FLAG_NUMBER, NULL, value, size, NULL) == 0) {
            return -1;
        }
        return value.getInt() & 0xFFFFFFFFL;
    }

    private static String hostOf(String url) {
        var rest = url.startsWith("https://") ? url.substring(8) : url.substring(7);
        var slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static String pathOf(String url) {
        var rest = url.startsWith("https://") ? url.substring(8) : url.substring(7);
        var slash = rest.indexOf('/');
        return slash < 0 ? "/" : rest.substring(slash);
    }
}
