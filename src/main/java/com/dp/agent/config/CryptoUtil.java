package com.dp.agent.config;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * API Key 的本地加密（AES-128-GCM）。
 *
 * 密钥为首次运行时随机生成的 16 字节，保存在配置目录下的 secret.key；
 * 密文格式 "gcm:" + Base64(iv[12] + ciphertext)。
 *
 * 坦率的边界说明：密钥与密文同机同目录，能防"配置文件被单独拷走/误传"这类
 * 最常见的泄露路径，防不了对本机有完整控制权的攻击者。这是桌面工具的
 * 常规取舍，不是密码学保险箱。
 */
public final class CryptoUtil {

    private static final String PREFIX = "gcm:";
    private static final int IV_BYTES = 12;
    private static final int KEY_BYTES = 16;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    private CryptoUtil(SecretKey key) {
        this.key = key;
    }

    /** 加载目录下既有密钥；不存在则生成并原子写入 secret.key。 */
    public static CryptoUtil forDirectory(Path dir) throws IOException {
        Path keyFile = dir.resolve("secret.key");
        byte[] raw;
        if (Files.exists(keyFile)) {
            try {
                raw = Base64.getDecoder().decode(Files.readString(keyFile).trim());
            } catch (IllegalArgumentException e) {
                throw new IOException("secret.key 内容损坏（非 Base64），"
                        + "请将其移开后重启以重新生成密钥（需重新录入 API Key）", e);
            }
            if (raw.length != KEY_BYTES) {
                throw new IOException("secret.key 内容损坏（长度 " + raw.length + "），"
                        + "请将其移开后重启以重新生成密钥（需重新录入 API Key）");
            }
        } else {
            raw = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(raw);
            writeSecret(dir, keyFile, Base64.getEncoder().encodeToString(raw));
        }
        return new CryptoUtil(new SecretKeySpec(raw, "AES"));
    }

    /** 原子写入密钥文件，并尽力收紧为仅当前用户可读写。 */
    private static void writeSecret(Path dir, Path keyFile, String encoded) throws IOException {
        Path tmp = dir.resolve("secret.key." + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try {
            Files.writeString(tmp, encoded, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, keyFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, keyFile);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp);
            }
        }
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 下由 %APPDATA% 的 ACL 负责；其他平台不支持 POSIX 权限时跳过
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密 API Key 失败", e);
        }
    }

    /** 解密；对历史明文/空值宽容处理（原样返回），避免升级后配置不可用。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        if (!stored.startsWith(PREFIX)) {
            return stored; // 兼容未加密的历史配置
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ""; // 密钥不匹配等情况：返回空，由上层提示用户重新填写
        }
    }
}
