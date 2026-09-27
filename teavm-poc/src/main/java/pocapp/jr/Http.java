package pocapp.jr;

import org.teavm.interop.Address;

import static pocapp.jr.N.*;

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
        var host = hostOf(url);
        var path = pathOf(url);
        var https = url.startsWith("https://");

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
            if (request.toLong() == 0 || !sendAndReceive(request)) {
                WinApi.winHttpCloseHandle(connect);
                WinApi.winHttpCloseHandle(session);
                return false;
            }

            var contentLength = intVar();
            var lengthSize = intVar();
            lengthSize.putInt(4);
            WinApi.winHttpQueryHeaders(request, WinApi.WINHTTP_QUERY_CONTENT_LENGTH | WinApi.WINHTTP_QUERY_FLAG_NUMBER,
                    NULL, contentLength, lengthSize, NULL);
            var totalBytes = contentLength.getInt();

            var out = WinApi.fopen(cstr(outPath), cstr("wb"));
            if (out.toLong() == 0) {
                WinApi.winHttpCloseHandle(request);
                WinApi.winHttpCloseHandle(connect);
                WinApi.winHttpCloseHandle(session);
                return false;
            }

            var buf = alloc(READ_CHUNK);
            var available = intVar();
            var read = intVar();
            var totalRead = 0L;
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
        return WinApi.winHttpSendRequest(request, NULL, 0, NULL, 0, 0, 0L) != 0
                && WinApi.winHttpReceiveResponse(request, NULL) != 0;
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
