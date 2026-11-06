package com.goldmonitor.http;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Entry point for all network access.
 *
 * <ul>
 *   <li>API 21+ uses {@link HttpsURLConnection} with a TLS factory that restricts the connection
 *       to TLS 1.2 and RSA cipher suites, plus a trust manager that understands the root CAs used
 *       by the price hosts.</li>
 *   <li>API 14-20 uses the Bouncy Castle TLS 1.2 stack, because Android 4.0.x has no TLS 1.2 at
 *       all and Android 4.1-4.4 cannot negotiate the GCM cipher suites the data host requires.</li>
 * </ul>
 */
public final class Http {

    private static final String TAG = "GoldMonitor";

    public static final String REFERER = "https://goldprice.org/";
    public static final String ORIGIN = "https://goldprice.org/";

    private static final String[] ANCHOR_FILES = new String[] {
            "anchors/isrg-root-x1.pem",
            "anchors/gts-root-r1.pem",
            "anchors/gts-root-r1-cross.pem",
            "anchors/gts-root-r4.pem"
    };

    /**
     * The data host only accepts AES-GCM suites, and charts.goldprice.org picks its RSA
     * certificate when no ECDSA suite is offered. Keeping the list RSA-only makes the selected
     * certificate chain deterministic on every Android version.
     */
    private static final String[] CIPHER_SUITES = new String[] {
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"
    };

    private static X509Certificate[] anchors;
    private static SSLSocketFactory socketFactory;

    private Http() {
    }

    /** Loads the bundled trust anchors. Safe to call more than once. */
    public static synchronized void init(Context context) {
        if (anchors != null) {
            return;
        }
        List<X509Certificate> loaded = new ArrayList<X509Certificate>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (int i = 0; i < ANCHOR_FILES.length; i++) {
                InputStream input = null;
                try {
                    input = context.getAssets().open(ANCHOR_FILES[i]);
                    Collection<? extends java.security.cert.Certificate> certificates =
                            factory.generateCertificates(input);
                    for (java.security.cert.Certificate certificate : certificates) {
                        loaded.add((X509Certificate) certificate);
                    }
                } finally {
                    if (input != null) {
                        try {
                            input.close();
                        } catch (IOException ignored) {
                            // Best effort only.
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not load bundled trust anchors: " + e);
        }
        anchors = loaded.toArray(new X509Certificate[loaded.size()]);
        LegacyHttps.setAnchors(anchors);
    }

    public static HttpResponse get(String url) throws IOException {
        if (Build.VERSION.SDK_INT >= 21) {
            return getModern(url);
        }
        return LegacyHttps.get(url, REFERER, ORIGIN);
    }

    /** Releases keep-alive connections held by the legacy transport. */
    public static void closeIdleConnections() {
        LegacyHttps.closeAll();
    }

    private static HttpResponse getModern(String urlString) throws IOException {
        HttpResponse response = getModernOnce(urlString);
        // The origin occasionally answers 304 even when no conditional request was sent, so a
        // refresh retries once with a cache buster instead of falling back to old data.
        if (response.status == 304 && urlString.indexOf("_gp=") < 0) {
            String separator = urlString.indexOf('?') >= 0 ? "&" : "?";
            return getModernOnce(urlString + separator + "_gp=" + System.currentTimeMillis());
        }
        return response;
    }

    private static HttpResponse getModernOnce(String urlString) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);

        setHeader(connection, "User-Agent", "");
        setHeader(connection, "Referer", REFERER);
        setHeader(connection, "Origin", ORIGIN);
        setHeader(connection, "Accept", "*/*");
        setHeader(connection, "Cache-Control", "no-cache");

        connection.setSSLSocketFactory(getSocketFactory());

        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] body = maybeGunzip(input == null ? new byte[0] : readAll(input));
        String contentType = connection.getContentType();
        String location = connection.getHeaderField("Location");
        connection.disconnect();
        return new HttpResponse(status, body, contentType, location);
    }

    private static void setHeader(HttpsURLConnection connection, String name, String value) {
        try {
            connection.setRequestProperty(name, value);
        } catch (RuntimeException ignored) {
            // Some platform versions reject empty header values; the Referer header is the one
            // the price hosts actually require, so a dropped User-Agent is harmless.
        }
    }

    private static synchronized SSLSocketFactory getSocketFactory() throws IOException {
        if (socketFactory != null) {
            return socketFactory;
        }
        try {
            X509TrustManager platform = null;
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            TrustManager[] managers = factory.getTrustManagers();
            for (int i = 0; i < managers.length; i++) {
                if (managers[i] instanceof X509TrustManager) {
                    platform = (X509TrustManager) managers[i];
                }
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] { new AnchorTrustManager(platform, anchors) },
                    new SecureRandom());
            socketFactory = new StrictSocketFactory(context.getSocketFactory());
            return socketFactory;
        } catch (Exception e) {
            throw new IOException("Unable to initialise TLS: " + e);
        }
    }

    /**
     * goldprice.org sometimes gzips even though identity was requested. HttpsURLConnection
     * normally decompresses for us, so only a real gzip magic header triggers this path.
     */
    private static byte[] maybeGunzip(byte[] body) {
        if (body.length < 2 || (body[0] & 0xFF) != 0x1F || (body[1] & 0xFF) != 0x8B) {
            return body;
        }
        try {
            java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                    new ByteArrayInputStream(body));
            try {
                return readAll(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return body;
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > 8 * 1024 * 1024) {
                    throw new IOException("response body too large");
                }
            }
            return output.toByteArray();
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // Best effort only.
            }
        }
    }

    /**
     * Accepts a chain if either the platform store or one of the bundled anchors can validate
     * it. Host name verification is left to HttpsURLConnection's default verifier.
     */
    private static final class AnchorTrustManager implements X509TrustManager {

        private final X509TrustManager platform;
        private final X509Certificate[] trustedAnchors;

        AnchorTrustManager(X509TrustManager platform, X509Certificate[] trustedAnchors) {
            this.platform = platform;
            this.trustedAnchors = trustedAnchors;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            if (platform == null) {
                throw new java.security.cert.CertificateException("client certificates not supported");
            }
            platform.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            if (platform != null) {
                try {
                    platform.checkServerTrusted(chain, authType);
                    return;
                } catch (java.security.cert.CertificateException ignored) {
                    // Fall through to the bundled anchors.
                }
            }
            ChainVerifier.verify(chain, trustedAnchors, null);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            if (platform != null) {
                return platform.getAcceptedIssuers();
            }
            return trustedAnchors == null ? new X509Certificate[0] : trustedAnchors;
        }
    }

    /** Forces TLS 1.2 plus RSA cipher suites on sockets created by the platform factory. */
    private static final class StrictSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory delegate;

        StrictSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket() throws IOException {
            return configure((SSLSocket) delegate.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return configure((SSLSocket) delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return configure((SSLSocket) delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return configure((SSLSocket) delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                int localPort) throws IOException {
            return configure((SSLSocket) delegate.createSocket(address, port, localAddress,
                    localPort));
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                throws IOException {
            return configure((SSLSocket) delegate.createSocket(socket, host, port, autoClose));
        }

        private SSLSocket configure(SSLSocket socket) {
            try {
                List<String> supportedProtocols = Arrays.asList(socket.getSupportedProtocols());
                if (supportedProtocols.contains("TLSv1.2")) {
                    socket.setEnabledProtocols(new String[] { "TLSv1.2" });
                }
                List<String> supportedCiphers = Arrays.asList(socket.getSupportedCipherSuites());
                List<String> enabled = new ArrayList<String>();
                for (int i = 0; i < CIPHER_SUITES.length; i++) {
                    if (supportedCiphers.contains(CIPHER_SUITES[i])) {
                        enabled.add(CIPHER_SUITES[i]);
                    }
                }
                if (!enabled.isEmpty()) {
                    socket.setEnabledCipherSuites(enabled.toArray(new String[enabled.size()]));
                }
            } catch (RuntimeException ignored) {
                // Keep the platform defaults if the socket rejects explicit configuration.
            }
            return socket;
        }
    }
}
