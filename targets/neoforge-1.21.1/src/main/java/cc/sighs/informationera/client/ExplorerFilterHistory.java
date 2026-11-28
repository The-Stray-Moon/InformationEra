package cc.sighs.informationera.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 筛选条件的使用历史：按类别记录每个值的使用次数与最后使用时间。
 *
 * <p>高频按使用次数排序，同次数时最近使用的在前；最近按时间倒序。历史是全存档通用的
 * 界面习惯，序列化后由 {@link ExplorerPreferences} 写入客户端配置。这里不依赖任何
 * Minecraft 类型，便于单独测试排序、去重和裁剪。
 */
final class ExplorerFilterHistory {

    /** 每个类别最多保留的记录数，防止长期使用后配置无限增长。 */
    static final int MAX_RECORDS_PER_KIND = 80;

    private static final char FIELD_SEP = '|';

    static final class Record {
        final String kind;
        final String value;
        int uses;
        long lastAccess;

        Record(String kind, String value, int uses, long lastAccess) {
            this.kind = kind;
            this.value = value;
            this.uses = uses;
            this.lastAccess = lastAccess;
        }
    }

    private final Map<String, Record> records = new LinkedHashMap<>();

    private static String key(String kind, String value) {
        return kind + '\u0000' + value;
    }

    /** 记一次使用：已有记录累加次数并刷新时间，否则新建。 */
    void record(String kind, String value, long now) {
        if (kind == null || kind.isBlank() || value == null || value.isBlank()) {
            return;
        }
        String normalizedKind = kind.trim();
        String normalizedValue = value.trim();
        String key = key(normalizedKind, normalizedValue);
        Record record = records.get(key);
        if (record == null) {
            records.put(key, new Record(normalizedKind, normalizedValue, 1, now));
        } else {
            if (record.uses < Integer.MAX_VALUE) {
                record.uses++;
            }
            record.lastAccess = Math.max(record.lastAccess, now);
        }
        trim(normalizedKind);
    }

    /** 某类别按使用次数降序；次数相同按最后使用时间降序。 */
    List<String> top(String kind, int limit) {
        String normalizedKind = kind == null ? "" : kind.trim();
        List<Record> matches = new ArrayList<>();
        for (Record record : records.values()) {
            if (record.kind.equals(normalizedKind)) {
                matches.add(record);
            }
        }
        matches.sort(Comparator
                .comparingInt((Record record) -> record.uses).reversed()
                .thenComparing(Comparator.comparingLong((Record record) -> record.lastAccess).reversed()));
        return values(matches, limit);
    }

    /** 某类别按最后使用时间倒序。 */
    List<String> recent(String kind, int limit) {
        String normalizedKind = kind == null ? "" : kind.trim();
        List<Record> matches = new ArrayList<>();
        for (Record record : records.values()) {
            if (record.kind.equals(normalizedKind)) {
                matches.add(record);
            }
        }
        matches.sort(Comparator.comparingLong((Record record) -> record.lastAccess).reversed());
        return values(matches, limit);
    }

    private static List<String> values(List<Record> records, int limit) {
        List<String> result = new ArrayList<>();
        int size = limit <= 0 ? records.size() : Math.min(limit, records.size());
        for (int i = 0; i < size; i++) {
            result.add(records.get(i).value);
        }
        return result;
    }

    private void trim(String kind) {
        List<Record> matches = new ArrayList<>();
        for (Record record : records.values()) {
            if (record.kind.equals(kind)) {
                matches.add(record);
            }
        }
        if (matches.size() <= MAX_RECORDS_PER_KIND) {
            return;
        }
        matches.sort(Comparator.comparingLong((Record record) -> record.lastAccess));
        int excess = matches.size() - MAX_RECORDS_PER_KIND;
        for (int i = 0; i < excess; i++) {
            Record record = matches.get(i);
            records.remove(key(record.kind, record.value));
        }
    }

    /** 序列化为 {@code kind|value|uses|lastAccess}；值放在最后一个字段，避免解析错位。 */
    List<String> encode() {
        List<String> raw = new ArrayList<>(records.size());
        for (Record record : records.values()) {
            raw.add(record.kind + FIELD_SEP + record.uses + FIELD_SEP + record.lastAccess + FIELD_SEP + record.value);
        }
        return raw;
    }

    /** 解析配置；非法行跳过，同类别同值只保留第一条。 */
    static ExplorerFilterHistory decode(List<? extends String> raw) {
        ExplorerFilterHistory history = new ExplorerFilterHistory();
        if (raw == null) {
            return history;
        }
        for (String line : raw) {
            if (line == null) {
                continue;
            }
            String[] parts = line.split("\\" + FIELD_SEP, 4);
            if (parts.length < 4) {
                continue;
            }
            String kind = parts[0].trim();
            String value = parts[3].trim();
            if (kind.isEmpty() || value.isEmpty()) {
                continue;
            }
            int uses = parseInt(parts[1]);
            long lastAccess = parseLong(parts[2]);
            String key = key(kind, value);
            if (!history.records.containsKey(key)) {
                history.records.put(key, new Record(kind, value, uses, lastAccess));
            }
        }
        return history;
    }

    private static int parseInt(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static long parseLong(String raw) {
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }
}
