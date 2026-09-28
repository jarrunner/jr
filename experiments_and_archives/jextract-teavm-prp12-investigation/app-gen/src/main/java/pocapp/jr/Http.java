package pocapp.jr;

import org.teavm.interop.Address;

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

        var session = WinApi.winHttpOpen(Wstr.of("jr-launcher/1.0"), WinApi.WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                Address.fromInt(0), Address.fromInt(0), 0);
        if (session.toLong() == 0) {
            return null;
        }
        var connect = WinApi.winHttpConnect(session, Wstr.of(host), (short) (https ? 443 : 80), 0);
        if (connect.toLong() == 0) {
            WinApi.winHttpCloseHandle(session);
            return null;
        }
        var request = WinApi.winHttpOpenRequest(connect, Wstr.of("GET"), Wstr.of(path), Address.fromInt(0),
                Address.fromInt(0), Address.fromInt(0), https ? WinApi.WINHTTP_FLAG_SECURE : 0);
        if (request.toLong() == 0 || !sendAndReceive(request)) {
            WinApi.winHttpCloseHandle(connect);
            WinApi.winHttpCloseHandle(session);
            return null;
        }

        var sb = new StringBuilder();
        var buf = new byte[READ_CHUNK];
        var bufAddr = Address.ofData(buf);
        var availBuf = new byte[4];
        var availAddr = Address.ofData(availBuf);
        var readBuf = new byte[4];
        var readAddr = Address.ofData(readBuf);

        while (sb.length() < maxBytes) {
            if (WinApi.winHttpQueryDataAvailable(request, availAddr) == 0) {
                break;
            }
            var available = availAddr.getInt();
            if (available == 0) {
                break;
            }
            var toRead = Math.min(available, buf.length);
            if (WinApi.winHttpReadData(request, bufAddr, toRead, readAddr) == 0) {
                break;
            }
            var got = readAddr.getInt();
            if (got == 0) {
                break;
            }
            for (var i = 0; i < got; i++) {
                sb.append((char) (buf[i] & 0xFF));
            }
        }

        WinApi.winHttpCloseHandle(request);
        WinApi.winHttpCloseHandle(connect);
        WinApi.winHttpCloseHandle(session);
        return sb.toString();
    }

    public static boolean downloadToFile(String url, String outPath, Progress progress) {
        var host = hostOf(url);
        var path = pathOf(url);
        var https = url.startsWith("https://");

        var session = WinApi.winHttpOpen(Wstr.of("jr-launcher/1.0"), WinApi.WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                Address.fromInt(0), Address.fromInt(0), 0);
        if (session.toLong() == 0) {
            return false;
        }
        var connect = WinApi.winHttpConnect(session, Wstr.of(host), (short) (https ? 443 : 80), 0);
        if (connect.toLong() == 0) {
            WinApi.winHttpCloseHandle(session);
            return false;
        }
        var request = WinApi.winHttpOpenRequest(connect, Wstr.of("GET"), Wstr.of(path), Address.fromInt(0),
                Address.fromInt(0), Address.fromInt(0), https ? WinApi.WINHTTP_FLAG_SECURE : 0);
        if (request.toLong() == 0 || !sendAndReceive(request)) {
            WinApi.winHttpCloseHandle(connect);
            WinApi.winHttpCloseHandle(session);
            return false;
        }

        var lenBuf = new byte[4];
        var lenAddr = Address.ofData(lenBuf);
        var lenSizeBuf = new byte[4];
        var lenSizeAddr = Address.ofData(lenSizeBuf);
        lenSizeAddr.putInt(4);
        WinApi.winHttpQueryHeaders(request, WinApi.WINHTTP_QUERY_CONTENT_LENGTH | WinApi.WINHTTP_QUERY_FLAG_NUMBER,
                Address.fromInt(0), lenAddr, lenSizeAddr, Address.fromInt(0));
        var totalBytes = lenAddr.getInt();

        var out = WinApi.fopen(Cstr.of(outPath), Cstr.of("wb"));
        if (out.toLong() == 0) {
            WinApi.winHttpCloseHandle(request);
            WinApi.winHttpCloseHandle(connect);
            WinApi.winHttpCloseHandle(session);
            return false;
        }

        var buf = new byte[READ_CHUNK];
        var bufAddr = Address.ofData(buf);
        var availBuf = new byte[4];
        var availAddr = Address.ofData(availBuf);
        var readBuf = new byte[4];
        var readAddr = Address.ofData(readBuf);
        var totalRead = 0L;
        var ok = true;

        while (true) {
            if (WinApi.winHttpQueryDataAvailable(request, availAddr) == 0) {
                break;
            }
            var available = availAddr.getInt();
            if (available == 0) {
                break;
            }
            var toRead = Math.min(available, buf.length);
            if (WinApi.winHttpReadData(request, bufAddr, toRead, readAddr) == 0) {
                ok = false;
                break;
            }
            var got = readAddr.getInt();
            if (got == 0) {
                break;
            }
            WinApi.fwrite(bufAddr, 1, got, out);
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
    }

    private static boolean sendAndReceive(Address request) {
        return WinApi.winHttpSendRequest(request, Address.fromInt(0), 0, Address.fromInt(0), 0, 0, 0L) != 0
                && WinApi.winHttpReceiveResponse(request, Address.fromInt(0)) != 0;
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
