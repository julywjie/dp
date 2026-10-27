package com.dp.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实验室 5：AES-GCM——蜡封信封 + 防拆封条（AI-KNOWLEDGE.md 第 8 章配套）。
 * 运行：mvn test -Dtest=Lab05CryptoLabTest -Dsurefire.useFile=false
 */
class Lab05CryptoLabTest {

    @TempDir
    Path tempDir;

    @Test
    void exp1_gcmRoundTripAndTamperDetection() throws Exception {
        System.out.println("── 实验 5.1 加密落盘、解密还原、篡改即废 ──");
        CryptoUtil crypto = CryptoUtil.forDirectory(tempDir);
        String apiKey = "sk-demo-1234567890";

        String stored = crypto.encrypt(apiKey);
        System.out.println("明文   : " + apiKey);
        System.out.println("密文   : " + stored.substring(0, Math.min(48, stored.length())) + "…（gcm: 前缀 + Base64）");
        System.out.println("解密   : " + crypto.decrypt(stored));
        assertEquals(apiKey, crypto.decrypt(stored));

        String again = crypto.encrypt(apiKey);
        System.out.println("同一明文再加密一次密文就变（随机 IV）: " + (again.equals(stored) ? "相同" : "不同"));
        assertNotEquals(stored, again);

        String tampered = stored.length() > 10
                ? stored.substring(0, stored.length() - 2) + "AA"
                : stored + "AA";
        System.out.println("动过手脚的密文: " + tampered.substring(0, Math.min(48, tampered.length())) + "…");
        String tamperedResult = crypto.decrypt(tampered);
        System.out.println("解密结果: \"" + tamperedResult + "\"（空串！）");
        System.out.println("→ GCM 封条一破校验即失败，decrypt 静默返回空串，");
        System.out.println("   上层据此提示用户重新录入 Key——宁可要不到，不给假数据");
        assertEquals("", tamperedResult);
    }
}
