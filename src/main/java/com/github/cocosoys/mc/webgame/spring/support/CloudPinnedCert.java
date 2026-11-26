package com.github.cocosoys.mc.webgame.spring.support;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按设备证书 pin：只信任云端上报时登记的那一张证书。
 *
 * <p>这是“每设备证书 + 自动 pin”的服务端落点。它替代了“把每台机器的自签证书
 * 手工导入全局 JKS”这条运维路径：全局信任库一旦多一台机器就要改文件、重启，
 * 而且 JKS 里的任何一张证书都可能被拿去冒充另一台设备。这里只认该设备自己的
 * SHA-256，证书换了就拒绝，直到下一次上报把新指纹带上来。</p>
 *
 * <p>指纹校验发生在 TLS 握手阶段（{@link PinnedTrustManager}），主机名校验
 * 也改成“叶证书指纹必须等于 pin”。这样设备换 IP、证书 SAN 里只有旧地址时，
 * 仍然能靠指纹确认身份；不是简单地“跳过校验”。</p>
 */
public final class CloudPinnedCert {

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----");

    private CloudPinnedCert() {
    }

    /** 解析 PEM 里的第一张证书；失败返回 null（调用方回退到全局信任库并留下原因）。 */
    public static X509Certificate parse(String pem) {
        if (pem == null || pem.trim().isEmpty()) {
            return null;
        }
        try {
            Matcher m = PEM_BLOCK.matcher(pem);
            if (!m.find()) {
                return null;
            }
            byte[] der = Base64.getMimeDecoder().decode(m.group(1));
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (Throwable t) {
            return null;
        }
    }

    /** PEM 叶证书的 DER SHA-256（小写十六进制）；解析失败返回空串。 */
    public static String sha256(String pem) {
        X509Certificate cert = parse(pem);
        return cert == null ? "" : sha256(cert);
    }

    public static String sha256(X509Certificate cert) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 归一化指纹：去掉冒号/空格并转小写；空值返回空串。 */
    public static String normalizePin(String pin) {
        if (pin == null) {
            return "";
        }
        String s = pin.replace(":", "").replace(" ", "").trim().toLowerCase(Locale.ROOT);
        return s.matches("[0-9a-f]{64}") ? s : "";
    }

    /**
     * 构建只信任该证书的 SSLSocketFactory。
     *
     * @param pem 上报时登记的证书 PEM
     * @param pin 上报时登记的 SHA-256；空串时从 PEM 现算
     * @return null 表示 PEM 无法解析，调用方应回退到旧信任库
     */
    public static SSLSocketFactory factory(String pem, String pin) {
        final X509Certificate pinned = parse(pem);
        if (pinned == null) {
            return null;
        }
        final String want = normalizePin(pin).isEmpty() ? sha256(pinned) : normalizePin(pin);
        if (want.isEmpty()) {
            return null;
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new PinnedTrustManager(pinned, want)}, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只接受叶证书指纹等于 pin 的会话；不是“全放行”，所以换一张证书就握手失败。 */
    public static HostnameVerifier hostnameVerifier(final String pin) {
        final String want = normalizePin(pin);
        return new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession session) {
                if (want.isEmpty()) {
                    return false;
                }
                try {
                    java.security.cert.Certificate[] chain = session.getPeerCertificates();
                    if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
                        return false;
                    }
                    return want.equals(sha256((X509Certificate) chain[0]));
                } catch (Throwable t) {
                    return false;
                }
            }
        };
    }

    /** 只信任单张证书的 TrustManager；链上出现别的证书一律拒绝。 */
    private static final class PinnedTrustManager implements X509TrustManager {

        private final X509Certificate pinned;
        private final String pin;

        PinnedTrustManager(X509Certificate pinned, String pin) {
            this.pinned = pinned;
            this.pin = pin;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("pinned trust manager is server-side only");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("empty server certificate chain");
            }
            String got = sha256(chain[0]);
            if (got.isEmpty() || !pin.equals(got)) {
                throw new CertificateException("pinned certificate mismatch: expected " + pin + ", got " + got);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[]{pinned};
        }
    }
}
