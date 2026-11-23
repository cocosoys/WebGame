package com.github.cocosoys.mc.webgame.spring.support;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 云游戏客户端健康探针：{@code GET http(s)://<ip>:<port>/api/v1/health}。
 *
 * <p>协议依据（cloud-stream / stream-server）：HTTP 路由里 {@code Route::Health} 对应
 * {@code /api/v1/health}，载荷含 {@code ok / verdict / server{version,uptime_s,listen}}；
 * 若客户端配置了 {@code auth_token}，该端点要求凭证，头部形态为
 * {@code Authorization: Bearer <token>}（同一份 {@code auth_token} 即本插件记录里的
 * X-Cloud-Key）。</p>
 *
 * <p>刻意不引入 JSON 库：Spigot 运行时不保证 gson 在 WebGame 的编译类路径上，而这里
 * 只需读 5 个固定字段，用正则从自家服务端的固定输出里取值即可；解析失败不影响
 * “网络可达”这一结论（状态仍可判 online）。</p>
 */
public final class CloudClientProbe {

    /** 固定路径：cloud-stream 的健康端点。 */
    private static final String HEALTH_PATH = "/api/v1/health";

    /**
     * 显式放行的信任策略（仅当 {@code cloud.probe.trust-all=true} 时使用）。
     *
     * <p>默认<b>不</b>放行：插件走 JVM 的信任库（服务端已用 {@code -Djavax.net.ssl.trustStore}
     * 指向 trust-cloud.jks），证书链与主机名照常校验。全部放行意味着任何能劫持内网流量的人
     * 都可以伪造一台"健康"的云机（探针只读版本号，不校验身份），所以它必须是显式开关，
     * 而不是默认行为。</p>
     */
    private static final SSLSocketFactory TRUST_ALL = buildTrustAll();

    private static final Pattern P_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern P_UPTIME = Pattern.compile("\"uptime_s\"\\s*:\\s*(\\d+)");
    private static final Pattern P_LISTEN = Pattern.compile("\"listen\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern P_OK = Pattern.compile("\"ok\"\\s*:\\s*(true|false)");

    private CloudClientProbe() {
    }

    /**
     * 一次探测结果。
     *
     * <ul>
     *   <li>{@link #reachable}：TCP/HTTP 层是否连上（false=拒绝/超时/DNS 失败）；
     *   <li>{@link #authorized}：是否通过凭证（401/403 为 false，区别于不可达）；
     *   <li>{@link #version / #uptimeSeconds / #listen}：客户端自报信息（可能为空）。
     * </ul>
     */
    public static final class Result {

        public final boolean reachable;
        public final boolean authorized;
        public final int httpStatus;
        public final String version;
        public final long uptimeSeconds;
        public final String listen;
        public final long latencyMs;
        public final String error;
        /** 本次结果是不是走 HTTPS 拿到的。 */
        public final boolean tls;

        Result(boolean reachable, boolean authorized, int httpStatus, String version,
               long uptimeSeconds, String listen, long latencyMs, String error) {
            this(reachable, authorized, httpStatus, version, uptimeSeconds, listen, latencyMs, error, false);
        }

        Result(boolean reachable, boolean authorized, int httpStatus, String version,
               long uptimeSeconds, String listen, long latencyMs, String error, boolean tls) {
            this.reachable = reachable;
            this.authorized = authorized;
            this.httpStatus = httpStatus;
            this.version = version == null ? "" : version;
            this.uptimeSeconds = Math.max(0L, uptimeSeconds);
            this.listen = listen == null ? "" : listen;
            this.latencyMs = Math.max(0L, latencyMs);
            this.error = error == null ? "" : error;
            this.tls = tls;
        }
    }

    public static Result probe(String host, int port, String key, int timeoutMs) {
        return probe(host, port, key, timeoutMs, false, false);
    }

    public static Result probe(String host, int port, String key, int timeoutMs, boolean tls) {
        return probe(host, port, key, timeoutMs, tls, false);
    }

    /**
     * 探测一台设备。
     *
     * @param host      设备 IP 或主机名（IPv6 自动加方括号）
     * @param port      端口（同一端口先嗅探后握手，http/https 只是 scheme 不同）
     * @param key       共享密钥（空=不带凭证；空密钥只在客户端未配置 auth_token 时才会 200）
     * @param timeoutMs 连接/读取超时（毫秒）
     * @param tls       true = 走 HTTPS
     * @param trustAll  true = 跳过证书链与主机名校验（仅 {@code cloud.probe.trust-all=true}）
     */
    public static Result probe(String host, int port, String key, int timeoutMs, boolean tls, boolean trustAll) {
        long start = System.nanoTime();
        HttpURLConnection conn = null;
        try {
            URL url = new URL((tls ? "https://" : "http://") + bracket(host) + ":" + port + HEALTH_PATH);
            conn = (HttpURLConnection) url.openConnection();
            if (tls && conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                if (trustAll) {
                    if (TRUST_ALL != null) {
                        https.setSSLSocketFactory(TRUST_ALL);
                    }
                    // 自签证书的 CN/SAN 未必等于管理员填的那个字符串（DNS 名/别名都常见）。
                    https.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                            return true;
                        }
                    });
                }
                // trustAll=false：不覆盖任何 SSL 设置 —— 交给 JVM 信任库与默认主机名校验
            }
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            // 禁止跟随重定向：避免管理员输入的地址把服务器带去任意目标（SSRF 收敛）。
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/json");
            if (key != null && !key.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            int code = conn.getResponseCode();
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            if (code == 401 || code == 403) {
                closeQuietly(conn);
                return new Result(true, false, code, "", 0, "", elapsed, "HTTP " + code, tls);
            }
            if (code != 200) {
                closeQuietly(conn);
                return new Result(true, true, code, "", 0, "", elapsed, "HTTP " + code, tls);
            }
            String body = readAll(conn.getInputStream());
            long up = 0L;
            Matcher mu = P_UPTIME.matcher(body);
            if (mu.find()) {
                try {
                    up = Long.parseLong(mu.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
            String version = firstGroup(P_VERSION, body);
            String listen = firstGroup(P_LISTEN, body);
            boolean okFlag = "true".equals(firstGroup(P_OK, body));
            return new Result(true, true, code, version, up, listen, elapsed,
                    okFlag ? "" : "health.ok=false", tls);
        } catch (Throwable t) {
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            String msg = t.getClass().getSimpleName();
            if (t.getMessage() != null && !t.getMessage().isEmpty()) {
                msg = msg + ": " + t.getMessage();
            }
            // 证书问题与"网络不通"必须能分辨：前者要导证书/改信任库，后者查网线/防火墙
            if (t instanceof javax.net.ssl.SSLHandshakeException
                    || t instanceof java.security.cert.CertificateException
                    || msg.contains("PKIX")
                    || msg.toLowerCase(java.util.Locale.ROOT).contains("certificate")) {
                msg = "TLS 证书校验失败：" + msg;
            }
            closeQuietly(conn);
            return new Result(false, false, 0, "", 0, "", elapsed, msg, tls);
        }
    }

    /** 全放行的信任库；初始化失败返回 null（此时 HTTPS 探测会退化为握手失败，不会静默 200）。 */
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

    private static String firstGroup(Pattern p, String body) {
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : "";
    }

    /** IPv6 字面量必须加方括号才能拼进 URL。 */
    private static String bracket(String host) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) {
            return "";
        }
        if (h.startsWith("[") || h.indexOf(':') < 0) {
            return h;
        }
        return "[" + h + "]";
    }

    private static String readAll(InputStream in) throws IOExceptionWrapper {
        StringBuilder sb = new StringBuilder(4096);
        try (InputStream is = in;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
                if (sb.length() > 64 * 1024) {
                    break; // 自家端点不会这么大；防御性截断
                }
            }
        } catch (Throwable t) {
            throw new IOExceptionWrapper(t);
        }
        return sb.toString();
    }

    private static void closeQuietly(HttpURLConnection conn) {
        try {
            if (conn != null) {
                conn.disconnect();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 受检异常包装：readAll 只在本类内部用，避免为它写一整套 throws 声明。 */
    static final class IOExceptionWrapper extends Exception {
        IOExceptionWrapper(Throwable cause) {
            super(cause);
        }
    }
}
