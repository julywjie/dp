package com.dp.agent.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆召回：不依赖 embedding 的轻量检索。
 * 中文按二元组、英文按词做 token 重叠评分，叠加重要度与新鲜度；
 * 通用记忆（general=true）始终优先注入，长期记忆按相关度召回。
 */
public final class MemoryRetriever {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9][a-z0-9_\\-\\.]{1,}");
    private static final double MIN_SCORE = 0.04;

    private MemoryRetriever() {
    }

    public static List<MemoryEntry> select(List<MemoryEntry> all, String query,
                                           int maxEntries, int maxChars) {
        if (all == null || all.isEmpty() || maxEntries <= 0 || maxChars <= 0) {
            return List.of();
        }
        List<MemoryEntry> general = all.stream()
                .filter(MemoryEntry::general)
                .sorted(Comparator.comparing(MemoryEntry::pinned).reversed()
                        .thenComparing(Comparator.comparingInt(MemoryEntry::importance).reversed())
                        .thenComparing(Comparator.comparingLong(MemoryEntry::updatedAt).reversed()))
                .toList();

        Set<String> queryTokens = tokens(query);
        List<Scored> ranked = new ArrayList<>();
        if (!queryTokens.isEmpty()) {
            for (MemoryEntry entry : all) {
                if (entry.general()) {
                    continue;
                }
                double score = score(entry, queryTokens);
                if (score >= MIN_SCORE) {
                    ranked.add(new Scored(entry, score));
                }
            }
            ranked.sort(Comparator.comparingDouble(Scored::score).reversed()
                    .thenComparing(sc -> sc.entry().importance(), Comparator.reverseOrder())
                    .thenComparing(sc -> sc.entry().updatedAt(), Comparator.reverseOrder()));
        }

        List<MemoryEntry> selected = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int chars = 0;
        // 相关长期记忆优先占用约 60% 预算
        int relevantBudget = (int) (maxChars * 0.6);
        for (Scored scored : ranked) {
            if (selected.size() >= maxEntries) {
                break;
            }
            int cost = scored.entry().text().length();
            if (chars + cost > relevantBudget) {
                // 至少保证一条最相关的记忆能进入上下文
                if (selected.isEmpty() && chars + cost <= maxChars) {
                    add(selected, ids, scored.entry());
                    chars += cost;
                }
                continue;
            }
            add(selected, ids, scored.entry());
            chars += cost;
        }
        // 通用记忆至少保留一条，其余在总预算内补齐
        boolean generalAdded = false;
        for (MemoryEntry entry : general) {
            if (selected.size() >= maxEntries) {
                break;
            }
            int cost = entry.text().length();
            if (generalAdded && chars + cost > maxChars) {
                continue;
            }
            add(selected, ids, entry);
            chars += cost;
            generalAdded = true;
        }
        // 剩余预算继续给相关长期记忆
        for (Scored scored : ranked) {
            if (selected.size() >= maxEntries) {
                break;
            }
            if (ids.contains(scored.entry().id())) {
                continue;
            }
            int cost = scored.entry().text().length();
            if (chars + cost > maxChars) {
                continue;
            }
            add(selected, ids, scored.entry());
            chars += cost;
        }
        return selected;
    }

    private static void add(List<MemoryEntry> selected, Set<String> ids, MemoryEntry entry) {
        if (ids.add(entry.id())) {
            selected.add(entry);
        }
    }

    private static double score(MemoryEntry entry, Set<String> queryTokens) {
        Set<String> memoryTokens = tokens(entry.text() + " " + entry.kind().label());
        if (memoryTokens.isEmpty()) {
            return 0;
        }
        int intersection = 0;
        for (String token : queryTokens) {
            if (memoryTokens.contains(token)) {
                intersection++;
            }
        }
        if (intersection == 0) {
            return 0;
        }
        double cosine = intersection / Math.sqrt(queryTokens.size() * memoryTokens.size());
        double ageDays = Math.max(0, System.currentTimeMillis() - entry.updatedAt())
                / (double) (24 * 60 * 60 * 1000);
        double recency = 0.05 * Math.exp(-ageDays / 90.0);
        double importance = 0.02 * entry.importance();
        return cosine + recency + importance + (entry.pinned() ? 0.02 : 0);
    }

    /** 中文二元组 + 英文单词；供召回与去重共用。 */
    static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher matcher = ASCII_WORD.matcher(lower);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length(); ) {
            int codePoint = lower.codePointAt(i);
            if (isCjk(codePoint)) {
                cjk.appendCodePoint(codePoint);
            } else {
                addBigrams(cjk, result);
                cjk.setLength(0);
            }
            i += Character.charCount(codePoint);
        }
        addBigrams(cjk, result);
        return result;
    }

    private static void addBigrams(StringBuilder run, Set<String> result) {
        int length = run.length();
        if (length == 1) {
            result.add(run.toString());
            return;
        }
        for (int i = 0; i + 1 < length; i++) {
            result.add(run.substring(i, i + 2));
        }
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private record Scored(MemoryEntry entry, double score) {
    }
}
