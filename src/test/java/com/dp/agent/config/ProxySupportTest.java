package com.dp.agent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProxySupportTest {

    @AfterEach
    void cleanup() {
        ProxySupport.clear();
    }

    @Test
    void mapsHttpProxyToStandardProperties() {
        AppConfig config = new AppConfig();
        config.setProxyMode(AppConfig.ProxyMode.HTTP);
        config.setProxyHost("127.0.0.1");
        config.setProxyPort(7890);

        ProxySupport.apply(config);

        assertEquals("127.0.0.1", System.getProperty("http.proxyHost"));
        assertEquals("7890", System.getProperty("http.proxyPort"));
        assertEquals("127.0.0.1", System.getProperty("https.proxyHost"));
        assertEquals("7890", System.getProperty("https.proxyPort"));
    }

    @Test
    void noneModeClearsProxyProperties() {
        AppConfig config = new AppConfig();
        config.setProxyMode(AppConfig.ProxyMode.HTTP);
        config.setProxyHost("127.0.0.1");
        config.setProxyPort(7890);
        ProxySupport.apply(config);

        config.setProxyMode(AppConfig.ProxyMode.NONE);
        ProxySupport.apply(config);

        assertNull(System.getProperty("http.proxyHost"));
        assertNull(System.getProperty("https.proxyPort"));
    }
}
