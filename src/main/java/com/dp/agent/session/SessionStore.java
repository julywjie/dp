package com.dp.agent.session;

import com.dp.agent.storage.JsonFiles;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 会话文件读写：每个会话一个 JSON，写入采用临时文件 + 原子替换。
 * 纯 IO，不依赖 JavaFX；损坏文件逐个跳过，不影响其他会话。
 */
public final class SessionStore {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String SUFFIX = ".json";

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);

    public SessionStore(Path dataDir) throws IOException {
        this.dir = dataDir.resolve("sessions");
        Files.createDirectories(dir);
    }

    public Path directory() {
        return dir;
    }

    public List<SessionMeta> list() {
        List<SessionMeta> metas = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path file : stream) {
                try {
                    SessionData data = mapper.readValue(file.toFile(), SessionData.class);
                    if (data != null && data.getId() != null && !data.getId().isBlank()) {
                        metas.add(data.toMeta());
                    }
                } catch (Exception e) {
                    System.err.println("[dp-agent] 跳过损坏的会话文件 " + file.getFileName()
                            + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[dp-agent] 读取会话目录失败: " + e.getMessage());
        }
        metas.sort(Comparator.comparingLong(SessionMeta::updatedAt).reversed()
                .thenComparing(SessionMeta::id));
        return metas;
    }

    public Optional<SessionData> load(String id) {
        if (!isValidId(id)) {
            return Optional.empty();
        }
        Path file = dir.resolve(id + SUFFIX);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            SessionData data = mapper.readValue(file.toFile(), SessionData.class);
            if (data == null) {
                return Optional.empty();
            }
            data.sanitize();
            return Optional.of(data);
        } catch (Exception e) {
            System.err.println("[dp-agent] 读取会话失败 " + id + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public void save(SessionData data) throws IOException {
        if (data == null || !isValidId(data.getId())) {
            throw new IOException("非法的会话 ID");
        }
        JsonFiles.writeAtomically(dir.resolve(data.getId() + SUFFIX), mapper, data);
    }

    /** true 表示磁盘上已不存在该会话（包括本来就没有文件的内存会话）。 */
    public boolean delete(String id) {
        if (!isValidId(id)) {
            return false;
        }
        Path file = dir.resolve(id + SUFFIX);
        if (!Files.exists(file)) {
            return true;
        }
        try {
            Files.delete(file);
            return true;
        } catch (IOException e) {
            System.err.println("[dp-agent] 删除会话失败 " + id + ": " + e.getMessage());
            return false;
        }
    }

    public static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }
}
