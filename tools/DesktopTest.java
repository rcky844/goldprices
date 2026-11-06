import com.goldmonitor.http.HttpResponse;
import com.goldmonitor.http.LegacyHttps;

import java.io.FileInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop harness for the legacy (Bouncy Castle) transport. The same code runs on Android 4.0.
 */
public final class DesktopTest {

    private static final String REFERER = "https://goldprice.org/";
    private static final String ORIGIN = "https://goldprice.org/";

    private static final String[] URLS = new String[] {
            "https://data-asg.goldprice.org/dbXRates/USD",
            "https://data-asg.goldprice.org/GetData/USD-XAU/1",
            "https://data-asg.goldprice.org/GetData/USD-XAG/1",
            "https://goldprice.org/performance-json/gold-price-performance-USD.json",
            "https://goldprice.org/performance-json/silver-price-performance-USD.json",
            "https://charts.goldprice.org/charts/gold_1d_o_USD_z.png",
            "https://charts.goldprice.org/charts/silver_1d_o_USD_z.png"
    };

    public static void main(String[] args) throws Exception {
        X509Certificate[] anchors = loadAnchors(args.length > 0 ? args[0] : "../assets/anchors");
        LegacyHttps.setAnchors(anchors);

        for (int i = 0; i < URLS.length; i++) {
            long started = System.currentTimeMillis();
            HttpResponse response = LegacyHttps.get(URLS[i], REFERER, ORIGIN);
            long elapsed = System.currentTimeMillis() - started;
            String preview;
            if (response.contentType != null && response.contentType.startsWith("image/")) {
                preview = "PNG magic=" + (response.body.length > 8 ? response.body[1] + "" + response.body[2] : "?");
            } else {
                String body = response.bodyAsString();
                preview = body.length() > 160 ? body.substring(0, 160) + "..." : body;
            }
            System.out.println("[" + (i + 1) + "] " + URLS[i]);
            System.out.println("    status=" + response.status
                    + " bytes=" + response.body.length
                    + " type=" + response.contentType
                    + " time=" + elapsed + "ms");
            System.out.println("    " + preview.replace('\n', ' '));
        }
        LegacyHttps.closeAll();
        System.out.println("DONE");
    }

    private static X509Certificate[] loadAnchors(String directory) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> anchors = new ArrayList<X509Certificate>();
        String[] files = new String[] { "isrg-root-x1.pem", "gts-root-r1.pem",
                "gts-root-r1-cross.pem", "gts-root-r4.pem" };
        for (int i = 0; i < files.length; i++) {
            FileInputStream in = new FileInputStream(directory + "/" + files[i]);
            try {
                anchors.add((X509Certificate) factory.generateCertificate(in));
            } finally {
                in.close();
            }
        }
        return anchors.toArray(new X509Certificate[anchors.size()]);
    }
}
