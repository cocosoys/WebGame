package com.github.cocosoys.mc.webgame.spring.support;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.logging.Logger;

/**
 * WebGame 出站 TLS 使用的独立信任库（cloud-stream 自签证书专用）。
 *
 * <p>与 {@code -Djavax.net.ssl.trustStore} 的区别：只影响本插件主动访问云机的
 * 探针和控制指令，不需要改任何启动脚本，也不会把云机自签证书塞进 JDK 全局信任库。</p>
 *
 * <p>缓存按「路径 + 口令 + 类型 + 文件 mtime + 文件大小」失效：管理员往 JKS 里
 * 导入了新云机证书后，下一次探测会自动重新加载，不必重启 MC 服务。主机名校验
 * 仍由 {@link javax.net.ssl.HttpsURLConnection} 默认实现完成，SAN 必须匹配。</p>
 */
public final class CloudTrustStore {

    private static final Logger LOG = Logger.getLogger(CloudTrustStore.class.getName());
    private static final Object LOCK = new Object();

    private static volatile Loaded loaded;

    private CloudTrustStore() {
    }

    /**
     * 读取独立 truststore 并返回严格校验用的 socket factory。
     *
     * @param path     绝对路径；为空表示不启用独立信任库
     * @param password 口令，null/空按空口令处理
     * @param type     JKS / PKCS12；空按 JKS
     */
    public static SSLSocketFactory factory(String path, String password, String type) throws Exception {
        String p = path == null ? "" : path.trim();
        if (p.isEmpty()) {
            return null;
        }
        String t = type == null || type.trim().isEmpty() ? "JKS" : type.trim().toUpperCase(java.util.Locale.ROOT);
        String pass = password == null ? "" : password;
        File file = new File(p);
        if (!file.isFile()) {
            throw new java.io.IOException("独立信任库不存在：" + file.getAbsolutePath());
        }
        long modified = file.lastModified();
        long size = file.length();

        Loaded current = loaded;
        if (current != null
                && current.path.equals(file.getAbsolutePath())
                && current.type.equals(t)
                && current.password.equals(pass)
                && current.modified == modified
                && current.size == size) {
            return current.factory;
        }

        synchronized (LOCK) {
            current = loaded;
            if (current != null
                    && current.path.equals(file.getAbsolutePath())
                    && current.type.equals(t)
                    && current.password.equals(pass)
                    && current.modified == modified
                    && current.size == size) {
                return current.factory;
            }
            KeyStore ks = KeyStore.getInstance(t);
            try (InputStream in = new FileInputStream(file)) {
                ks.load(in, pass.toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
            SSLSocketFactory factory = ctx.getSocketFactory();
            loaded = new Loaded(file.getAbsolutePath(), t, pass, modified, size, factory);
            LOG.info("云机独立信任库已加载：" + file.getAbsolutePath()
                    + "（" + t + "，" + size + " B）");
            return factory;
        }
    }

    private static final class Loaded {
        final String path;
        final String type;
        final String password;
        final long modified;
        final long size;
        final SSLSocketFactory factory;

        Loaded(String path, String type, String password, long modified, long size,
               SSLSocketFactory factory) {
            this.path = path;
            this.type = type;
            this.password = password;
            this.modified = modified;
            this.size = size;
            this.factory = factory;
        }
    }
}
