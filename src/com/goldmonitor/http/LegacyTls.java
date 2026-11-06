package com.goldmonitor.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Vector;

import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.NameType;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.ServerName;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.bouncycastle.util.Strings;

/**
 * A TLS 1.2 client built on Bouncy Castle's low level TLS API.
 *
 * <p>Android 4.0.x (API 14/15) only ships TLS 1.0, and the price hosts refuse anything below
 * TLS 1.2. Bouncy Castle implements TLS 1.2 in pure Java, which makes HTTPS possible on ICS
 * without native libraries. The code deliberately avoids the BC JSSE provider: that provider
 * references Java 7+ classes that do not exist on Android 4.0.</p>
 */
final class LegacyTls {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Only RSA cipher suites are offered, so that charts.goldprice.org always selects its RSA
     * certificate (GTS Root R1) instead of the ECDSA one. All of these are supported by both
     * goldprice.org and CloudFront.
     */
    private static final int[] CIPHER_SUITES = new int[] {
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
    };

    static final class Connection {
        final Socket socket;
        final TlsClientProtocol protocol;
        final InputStream input;
        final OutputStream output;

        Connection(Socket socket, TlsClientProtocol protocol) {
            this.socket = socket;
            this.protocol = protocol;
            this.input = protocol.getInputStream();
            this.output = protocol.getOutputStream();
        }

        void close() {
            try {
                protocol.close();
            } catch (Exception ignored) {
                // Best effort only.
            }
            try {
                socket.close();
            } catch (Exception ignored) {
                // Best effort only.
            }
        }
    }

    private LegacyTls() {
    }

    static Connection connect(String host, int port, int connectTimeoutMs, int readTimeoutMs,
            final X509Certificate[] anchors) throws IOException {
        Socket socket = new Socket();
        boolean success = false;
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            socket.setTcpNoDelay(true);

            final String serverName = host;
            TlsClientProtocol protocol = new TlsClientProtocol(socket.getInputStream(),
                    socket.getOutputStream());
            protocol.connect(new DefaultTlsClient(new BcTlsCrypto(RANDOM)) {

                @Override
                public ProtocolVersion[] getProtocolVersions() {
                    return new ProtocolVersion[] { ProtocolVersion.TLSv12 };
                }

                @Override
                protected int[] getSupportedCipherSuites() {
                    return CIPHER_SUITES;
                }

                @Override
                @SuppressWarnings("rawtypes")
                protected Vector getSNIServerNames() {
                    Vector names = new Vector();
                    names.addElement(new ServerName(NameType.host_name,
                            Strings.toByteArray(serverName)));
                    return names;
                }

                @Override
                public TlsAuthentication getAuthentication() throws IOException {
                    return new TlsAuthentication() {

                        @Override
                        public void notifyServerCertificate(TlsServerCertificate serverCertificate)
                                throws IOException {
                            TlsCertificate[] list = serverCertificate.getCertificate()
                                    .getCertificateList();
                            if (list == null || list.length == 0) {
                                throw new TlsFatalAlert(AlertDescription.bad_certificate,
                                        "empty server certificate chain");
                            }
                            X509Certificate[] chain = new X509Certificate[list.length];
                            try {
                                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                                for (int i = 0; i < list.length; i++) {
                                    chain[i] = (X509Certificate) factory.generateCertificate(
                                            new ByteArrayInputStream(list[i].getEncoded()));
                                }
                                ChainVerifier.verify(chain, anchors, serverName);
                            } catch (CertificateException e) {
                                throw new TlsFatalAlert(AlertDescription.bad_certificate,
                                        e.getMessage());
                            }
                        }

                        @Override
                        public TlsCredentials getClientCredentials(CertificateRequest request) {
                            return null; // No client certificate authentication.
                        }
                    };
                }
            });

            Connection connection = new Connection(socket, protocol);
            success = true;
            return connection;
        } finally {
            if (!success) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Best effort only.
                }
            }
        }
    }
}
