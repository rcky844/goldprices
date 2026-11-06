package com.goldmonitor.http;

import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Verifies a server certificate chain against a set of bundled trust anchors and checks that the
 * leaf certificate matches the host name.
 *
 * <p>This exists because the price hosts are secured with root CAs that post-date Android 4.x.
 * The system trust store on such devices does not contain ISRG Root X1 (2015) or GTS Root R1
 * (2016), so the chain can never validate against the platform store alone.</p>
 */
public final class ChainVerifier {

    private ChainVerifier() {
    }

    public static void verify(X509Certificate[] chain, X509Certificate[] anchors, String host)
            throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("empty server certificate chain");
        }
        if (anchors == null || anchors.length == 0) {
            throw new CertificateException("no trusted certificates configured");
        }

        for (int i = 0; i < chain.length; i++) {
            if (chain[i] == null) {
                throw new CertificateException("null certificate at position " + i);
            }
            chain[i].checkValidity();
        }

        X509Certificate current = chain[0];
        for (int i = 1; i < chain.length; i++) {
            X509Certificate issuer = chain[i];
            if (!isSignedBy(issuer, current)) {
                throw new CertificateException("certificate chain is not correctly signed at position " + i);
            }
            if (issuer.getBasicConstraints() < 0) {
                throw new CertificateException("certificate at position " + i + " is not a CA");
            }
            current = issuer;
        }

        boolean trusted = false;
        for (int i = 0; i < anchors.length; i++) {
            X509Certificate anchor = anchors[i];
            if (anchor == null) {
                continue;
            }
            try {
                anchor.checkValidity();
            } catch (CertificateException e) {
                continue;
            }
            if (current.equals(anchor) || isSignedBy(anchor, current)) {
                trusted = true;
                break;
            }
        }
        if (!trusted) {
            StringBuilder message = new StringBuilder("server certificate is not issued by a trusted CA");
            message.append(" [chain:");
            for (int i = 0; i < chain.length; i++) {
                message.append(' ').append(i).append('=')
                        .append(chain[i].getSubjectX500Principal().getName())
                        .append(" <- ").append(chain[i].getIssuerX500Principal().getName());
            }
            message.append("]");
            throw new CertificateException(message.toString());
        }

        if (host != null) {
            verifyHostname(chain[0], host);
        }
    }

    private static boolean isSignedBy(X509Certificate issuer, X509Certificate certificate) {
        if (!certificate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
            return false;
        }
        try {
            PublicKey key = issuer.getPublicKey();
            certificate.verify(key);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void verifyHostname(X509Certificate certificate, String host)
            throws CertificateException {
        String normalised = host.toLowerCase(Locale.US);
        boolean ipAddress = isIpAddress(normalised);

        Collection<List<?>> names = null;
        try {
            names = certificate.getSubjectAlternativeNames();
        } catch (Exception e) {
            names = null;
        }

        if (names != null) {
            for (List<?> entry : names) {
                if (entry == null || entry.size() < 2) {
                    continue;
                }
                Object typeValue = entry.get(0);
                Object nameValue = entry.get(1);
                if (!(typeValue instanceof Integer) || !(nameValue instanceof String)) {
                    continue;
                }
                int type = ((Integer) typeValue).intValue();
                String value = ((String) nameValue).toLowerCase(Locale.US);
                if (ipAddress) {
                    if (type == 7 && ipAddressMatches(normalised, value)) {
                        return;
                    }
                } else if (type == 2 && dnsMatches(normalised, value)) {
                    return;
                }
            }
            throw new CertificateException("server certificate does not match host " + host);
        }

        // No subjectAltName extension at all: fall back to the legacy common-name check.
        String commonName = commonName(certificate);
        if (!ipAddress && commonName != null
                && dnsMatches(normalised, commonName.toLowerCase(Locale.US))) {
            return;
        }
        throw new CertificateException("server certificate does not match host " + host);
    }

    private static String commonName(X509Certificate certificate) {
        String dn;
        try {
            dn = certificate.getSubjectX500Principal().getName();
        } catch (Exception e) {
            return null;
        }
        String[] parts = dn.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.startsWith("CN=")) {
                return part.substring(3);
            }
        }
        return null;
    }

    private static boolean dnsMatches(String host, String pattern) {
        if (pattern.equals(host)) {
            return true;
        }
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1); // includes the leading dot
            if (!host.endsWith(suffix)) {
                return false;
            }
            String label = host.substring(0, host.length() - suffix.length());
            return label.length() > 0 && label.indexOf('.') < 0;
        }
        return false;
    }

    private static boolean isIpAddress(String value) {
        if (value.indexOf(':') >= 0) {
            return true; // IPv6 literal
        }
        int dots = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                dots++;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return dots == 3;
    }

    private static boolean ipAddressMatches(String host, String certificateValue) {
        // The literal forms used by these hosts are IPv4 addresses; compare as text.
        return host.equals(certificateValue);
    }
}
