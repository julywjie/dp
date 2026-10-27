package com.dp.agent.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * JSON 落盘工具：同目录唯一临时文件 + fsync + 原子替换。
 *
 * 三个持久化点（config / sessions / discover）共用同一套语义：
 * 写入要么完整成功，要么保留旧文件；进程崩溃最多留下一个 .tmp。
 */
public final class JsonFiles {

    private JsonFiles() {
    }

    public static void writeAtomically(Path target, ObjectMapper mapper, Object value)
            throws IOException {
        Path absolute = target.toAbsolutePath();
        Path dir = absolute.getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(absolute.getFileName() + "." + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try {
            mapper.writeValue(tmp.toFile(), value);
            force(tmp);
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    /** 尽力刷盘：部分文件系统不支持也不影响正确性，只影响掉电时的持久性。 */
    private static void force(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** 保留一份上一版本，读取失败时可人工回退。 */
    public static void backup(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try {
            Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 备份是尽力而为，不阻塞主写入
        }
    }
}
