package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.support.CloudTrustStore;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直接向 cloud-stream 发管理控制指令（不经过 session-agent）。
 *
 * <p>契约沿用 cloud-session-agent 的 StreamControl：</p>
 * <ul>
 *   <li>{@code POST /api/v1/control/game/restart?token=<cloud-key>}</li>
 *   <li>{@code Authorization: Bearer <cloud-key>}</li>
 *   <li>body 固定 {@code {}}</li>
 * </ul>
 *
 * <p>TLS 策略与健康探针一致：优先使用 {@code cloud.probe.trust-store} 独立信任库，
 * 未配置时退回 JVM 默认信任库；只有 {@code cloud.probe.trust-all=true} 才跳过证书链
 * 与主机名校验。</p>
 */
public final class CloudControlClient {

    private static final Logger LOG = Logger.getLogger(CloudControlClient.class.getName());
    private static final Pattern OK_RE = Pattern.compile("\"ok\"\\s*:\\s*(true|false)");
    private static final Pattern ERROR_RE = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");
    private static final int TIMEOUT_MS = 15_000;

    private static final SSLSocketFactory TRUST_ALL = buildTrustAll();
    private static final HostnameVerifier TRUST_ALL_HOST = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private CloudControlClient() {
    }

    /** 一次控制指令结果。 */
    public static final class Result {
        public final boolean ok;
        public final int httpStatus;
        public final String error;

        Result(boolean ok, int httpStatus, String error) {
            this.ok = ok;
            this.httpStatus = httpStatus;
            this.error = error == null ? "" : error;
        }
    }

    /** 重启云机上的游戏进程（由 cloud-stream 的 control 路由执行）。 */
    public static Result restart(CloudClient c, boolean trustAll) {
        return restart(c, trustAll, "", "", "");
    }

    /** 重启云机上的游戏进程；支持 WebGame 的独立 cloud-stream 信任库。 */
    public static Result restart(CloudClient c, boolean trustAll,
                                 String trustStorePath, String trustStorePassword, String trustStoreType) {
        if (c == null) {
            return new Result(false, 0, "云机记录为空");
        }
        String ip = c.getIp() == null ? "" : c.getIp().trim();
        int port = c.getPort();
        if (ip.isEmpty() || port <= 0 || port > 65535) {
            return new Result(false, 0, "云机未配置有效 ip/port");
        }
        String key = c.getCloudKey() == null ? "" : c.getCloudKey();
        if (key.isEmpty()) {
            return new Result(false, 0, "云机未登记共享密钥");
        }
        HttpURLConnection conn = null;
        try {
            String base = (c.isTls() ? "https://" : "http://") + bracket(ip) + ":" + port;
            String url = base + "/api/v1/control/game/restart?token="
                    + URLEncoder.encode(key, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            if (conn instanceof HttpsURLConnection && trustAll) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                if (TRUST_ALL != null) {
                    https.setSSLSocketFactory(TRUST_ALL);
                }
                https.setHostnameVerifier(TRUST_ALL_HOST);
            } else if (conn instanceof HttpsURLConnection) {
                SSLSocketFactory factory = CloudTrustStore.factory(
                        trustStorePath, trustStorePassword, trustStoreType);
                if (factory != null) {
                    ((HttpsURLConnection) conn).setSSLSocketFactory(factory);
                    // 不覆盖主机名校验：证书 SAN 必须匹配云机地址。
                }
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
            int code = conn.getResponseCode();
            String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code < 200 || code >= 300) {
                return new Result(false, code, "HTTP " + code + (body.isEmpty() ? "" : "：" + body));
            }
            String err = group(ERROR_RE, body);
            String okFlag = group(OK_RE, body);
            if ("false".equals(okFlag)) {
                return new Result(false, code, err.isEmpty() ? "控制端返回 ok=false" : err);
            }
            if (!err.isEmpty()) {
                return new Result(false, code, err);
            }
            return new Result(true, code, "");
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName();
            if (t.getMessage() != null && !t.getMessage().isEmpty()) {
                msg += ": " + t.getMessage();
            }
            if (msg.contains("PKIX") || msg.toLowerCase(java.util.Locale.ROOT).contains("certificate")) {
                msg = "TLS 证书校验失败：" + msg;
            }
            LOG.log(Level.FINE, "云机控制指令失败: " + msg);
            return new Result(false, 0, msg);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream is = in) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 64 * 1024) {
                    break;
                }
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String group(Pattern p, String body) {
        Matcher m = p.matcher(body == null ? "" : body);
        return m.find() ? m.group(1) : "";
    }

    private static String bracket(String host) {
        if (host.startsWith("[") || host.indexOf(':') < 0) {
            return host;
        }
        return "[" + host + "]";
    }

    private static SSLSocketFactory buildTrustAll() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Throwable t) {
            return null;
        }
    }
}
