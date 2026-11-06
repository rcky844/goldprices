package com.goldmonitor.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Small HTTP/1.1 client that runs on top of {@link LegacyTls}. It is used on Android releases
 * that cannot speak TLS 1.2 through the platform (API 14 and 15, i.e. Android 4.0.x).
 *
 * <p>Connections are kept alive per host so a refresh only needs three TLS handshakes even
 * though seven resources are downloaded.</p>
 */
public final class LegacyHttps {

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private static final Map<String, Connection> POOL = new HashMap<String, Connection>();
    private static X509Certificate[] anchors;

    private LegacyHttps() {
    }

    public static void setAnchors(X509Certificate[] trustedAnchors) {
        anchors = trustedAnchors;
    }

    public static HttpResponse get(String url, String referer, String origin) throws IOException {
        return get(url, referer, origin, 0);
    }

    private static HttpResponse get(String urlString, String referer, String origin, int redirects)
            throws IOException {
        URL url = new URL(urlString);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Only https URLs are supported: " + urlString);
        }
        String host = url.getHost();
        int port = url.getPort() > 0 ? url.getPort() : url.getDefaultPort();
        String key = host + ":" + port;

        IOException lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Connection connection;
            synchronized (POOL) {
                connection = POOL.get(key);
                if (connection == null) {
                    connection = new Connection(host, port);
                    POOL.put(key, connection);
                }
            }

            try {
                HttpResponse response = connection.fetch(url, referer, origin);
                if (connection.broken) {
                    synchronized (POOL) {
                        if (POOL.get(key) == connection) {
                            POOL.remove(key);
                        }
                    }
                    connection.close();
                }

                // The origin sometimes answers with 304 even though no conditional request was
                // sent. Retry once with a cache buster so a refresh always carries fresh data.
                if (response.status == 304 && urlString.indexOf("_gp=") < 0) {
                    String separator = urlString.indexOf('?') >= 0 ? "&" : "?";
                    return get(urlString + separator + "_gp=" + System.currentTimeMillis(),
                            referer, origin, redirects);
                }

                if (isRedirect(response.status) && redirects < 5
                        && response.location != null && response.location.length() > 0) {
                    URL next = new URL(url, response.location);
                    return get(next.toString(), referer, origin, redirects + 1);
                }
                return response;
            } catch (IOException e) {
                lastFailure = e;
                synchronized (POOL) {
                    if (POOL.get(key) == connection) {
                        POOL.remove(key);
                    }
                }
                connection.close();
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("request failed: " + urlString);
    }

    public static void closeAll() {
        synchronized (POOL) {
            for (Connection connection : POOL.values()) {
                connection.close();
            }
            POOL.clear();
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static final class Connection {

        private final String host;
        private final int port;
        private final LegacyTls.Connection tls;
        private boolean broken;

        Connection(String host, int port) throws IOException {
            this.host = host;
            this.port = port;
            this.tls = LegacyTls.connect(host, port, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, anchors);
        }

        HttpResponse fetch(URL url, String referer, String origin) throws IOException {
            String path = url.getFile();
            if (path == null || path.length() == 0) {
                path = "/";
            }

            StringBuilder request = new StringBuilder(256);
            request.append("GET ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(host).append("\r\n");
            request.append("User-Agent: \r\n");
            request.append("Referer: ").append(referer).append("\r\n");
            request.append("Origin: ").append(origin).append("\r\n");
            request.append("Accept: */*\r\n");
            request.append("Accept-Encoding: identity\r\n");
            request.append("Cache-Control: no-cache\r\n");
            request.append("Connection: keep-alive\r\n\r\n");

            OutputStream output = tls.output;
            output.write(request.toString().getBytes("ISO-8859-1"));
            output.flush();

            InputStream input = tls.input;

            int status;
            String statusLine;
            Map<String, String> headers;
            int interim = 0;
            do {
                statusLine = readLine(input);
                if (statusLine == null) {
                    throw new EOFException("connection closed before response");
                }
                status = parseStatus(statusLine);
                headers = readHeaders(input);
                interim++;
            } while (status >= 100 && status < 200 && interim < 4);

            String transferEncoding = header(headers, "transfer-encoding");
            String contentLengthHeader = header(headers, "content-length");
            String connectionHeader = header(headers, "connection");
            String contentEncoding = header(headers, "content-encoding");

            if (Boolean.getBoolean("gold.debug")) {
                System.out.println("[http] " + statusLine);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    System.out.println("[http]   " + entry.getKey() + ": " + entry.getValue());
                }
            }

            boolean closeAfter = connectionHeader != null
                    && connectionHeader.toLowerCase(Locale.US).indexOf("close") >= 0;

            byte[] body;
            if (status == 204 || status == 304) {
                body = new byte[0];
            } else if (transferEncoding != null
                    && transferEncoding.toLowerCase(Locale.US).indexOf("chunked") >= 0) {
                body = readChunked(input);
            } else if (contentLengthHeader != null) {
                long length;
                try {
                    length = Long.parseLong(contentLengthHeader.trim());
                } catch (NumberFormatException e) {
                    throw new IOException("invalid Content-Length: " + contentLengthHeader);
                }
                if (length < 0 || length > MAX_BODY_BYTES) {
                    closeAfter = true;
                    throw new IOException("refusing response body of " + length + " bytes");
                }
                body = readFixed(input, (int) length);
            } else {
                body = readToEnd(input);
                closeAfter = true;
            }

            if (contentEncoding != null
                    && contentEncoding.toLowerCase(Locale.US).indexOf("gzip") >= 0
                    && body.length > 0) {
                body = gunzip(body);
            }

            if (closeAfter) {
                broken = true;
                close();
            }

            return new HttpResponse(status, body, header(headers, "content-type"),
                    header(headers, "location"));
        }

        void close() {
            try {
                tls.close();
            } catch (Exception ignored) {
                // Best effort only.
            }
        }
    }

    private static String header(Map<String, String> headers, String name) {
        return headers.get(name);
    }

    private static int parseStatus(String statusLine) throws IOException {
        // e.g. "HTTP/1.1 200 OK"
        int firstSpace = statusLine.indexOf(' ');
        if (firstSpace < 0) {
            throw new IOException("malformed HTTP status line: " + statusLine);
        }
        int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
        String code = secondSpace < 0 ? statusLine.substring(firstSpace + 1)
                : statusLine.substring(firstSpace + 1, secondSpace);
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            throw new IOException("malformed HTTP status line: " + statusLine);
        }
    }

    private static Map<String, String> readHeaders(InputStream input) throws IOException {
        Map<String, String> headers = new HashMap<String, String>();
        while (true) {
            String line = readLine(input);
            if (line == null) {
                throw new EOFException("connection closed inside response headers");
            }
            if (line.length() == 0) {
                return headers;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            headers.put(name, value);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(80);
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                return new String(buffer.toByteArray(), "ISO-8859-1");
            }
            if (value != '\r') {
                buffer.write(value);
            }
            if (buffer.size() > 8192) {
                throw new IOException("HTTP header line too long");
            }
        }
        if (buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), "ISO-8859-1");
    }

    private static byte[] readFixed(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(result, offset, length - offset);
            if (read < 0) {
                throw new EOFException("connection closed after " + offset + " of " + length + " bytes");
            }
            offset += read;
        }
        return result;
    }

    private static byte[] readChunked(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(input);
            if (line == null) {
                throw new EOFException("connection closed inside chunked body");
            }
            int semicolon = line.indexOf(';');
            if (semicolon >= 0) {
                line = line.substring(0, semicolon);
            }
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }
            int size;
            try {
                size = Integer.parseInt(line, 16);
            } catch (NumberFormatException e) {
                throw new IOException("invalid chunk size: " + line);
            }
            if (size < 0 || result.size() + size > MAX_BODY_BYTES) {
                throw new IOException("response body too large");
            }
            if (size == 0) {
                // Consume optional trailers.
                while (true) {
                    String trailer = readLine(input);
                    if (trailer == null || trailer.length() == 0) {
                        break;
                    }
                }
                return result.toByteArray();
            }
            result.write(readFixed(input, size));
            readLine(input); // trailing CRLF
        }
    }

    private static byte[] readToEnd(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            result.write(buffer, 0, read);
            if (result.size() > MAX_BODY_BYTES) {
                throw new IOException("response body too large");
            }
        }
        return result.toByteArray();
    }

    /** Some responses are gzip encoded even when identity is requested. */
    private static byte[] gunzip(byte[] compressed) throws IOException {
        java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(compressed));
        try {
            return readToEnd(in);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Best effort only.
            }
        }
    }
}
