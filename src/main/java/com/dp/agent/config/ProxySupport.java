package com.dp.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把应用内的全局代理配置映射为 JVM 标准代理属性。
 *
 * JDK HttpClient、LangChain4j 的 JDK HTTP 客户端以及部分第三方库都会读取
 * 默认 ProxySelector，而默认 ProxySelector 会读取这些属性。修改后新创建的
 * 客户端生效；已创建的客户端可能需要重启应用。
 */
public final class ProxySupport {

    private static final Logger log = LoggerFactory.getLogger(ProxySupport.class);

    private ProxySupport() {
    }

    public static void apply(AppConfig config) {
        clear();
        if (config == null) {
            return;
        }
        AppConfig.ProxyMode mode = config.getProxyMode();
        String host = config.getProxyHost();
        int port = config.getProxyPort();
        if (mode == AppConfig.ProxyMode.NONE || host.isBlank() || port < 1 || port > 65535) {
            return;
        }
        try {
            if (mode == AppConfig.ProxyMode.HTTP) {
                System.setProperty("http.proxyHost", host);
                System.setProperty("http.proxyPort", String.valueOf(port));
                System.setProperty("https.proxyHost", host);
                System.setProperty("https.proxyPort", String.valueOf(port));
                System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1|[::1]");
            } else {
                System.setProperty("socksProxyHost", host);
                System.setProperty("socksProxyPort", String.valueOf(port));
            }
            System.setProperty("java.net.useSystemProxies", "false");
            log.info("网络代理已应用: {} {}:{}", mode, host, port);
        } catch (SecurityException e) {
            log.warn("设置网络代理失败: {}", e.getMessage());
        }
    }

    public static void clear() {
        clear("http.proxyHost");
        clear("http.proxyPort");
        clear("https.proxyHost");
        clear("https.proxyPort");
        clear("socksProxyHost");
        clear("socksProxyPort");
        clear("http.nonProxyHosts");
    }

    private static void clear(String key) {
        try {
            System.clearProperty(key);
        } catch (SecurityException e) {
            log.debug("清除代理属性失败 {}: {}", key, e.getMessage());
        }
    }
}
