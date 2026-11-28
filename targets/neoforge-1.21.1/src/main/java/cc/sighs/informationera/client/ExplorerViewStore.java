package cc.sighs.informationera.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 资源管理器显示偏好的内存存储。
 *
 * <p>作用域刻意分成两层：
 * <ul>
 *   <li>全局显示设置与默认视图模板按**本机玩家**保存（玩家 UUID），因此换世界、换服务器
 *       仍然沿用同一套界面习惯；</li>
 *   <li>每个文件夹的独立视图再按**磁盘身份**（储存卡的 {@code disk_uuid}）保存。不同卡上的
 *       {@code blocks}/{@code food}/根目录等内置文件夹 id 相同，只有带上磁盘身份才不会串用。</li>
 * </ul>
 *
 * <p>修改只落在具体的那一条记录上，不整体替换别的窗口、别的文件夹或别的磁盘的状态，
 * 因此多个窗口同时修改、切换磁盘时都不会互相串写。
 */
public final class ExplorerViewStore {

    private static final char FIELD_SEP = '|';
    /** 带版本后缀：旧的 viewState 行格式（identity 含服务器）不会被误读。 */
    private static final String TAG_DISPLAY = "g2";
    private static final String TAG_DEFAULT = "d2";
    private static final String TAG_FOLDER = "f2";

    /** 玩家 -> 全局显示设置。 */
    private final Map<String, ExplorerDisplayState> displays = new LinkedHashMap<>();
    /** 玩家 -> 默认视图模板。 */
    private final Map<String, ExplorerViewState> defaults = new LinkedHashMap<>();
    /** 玩家 -> 磁盘 -> 文件夹 -> 独立视图。 */
    private final Map<String, Map<String, Map<String, ExplorerViewState>>> folders = new LinkedHashMap<>();

    /** 某玩家的全局显示设置；没有记录时返回内置默认值。 */
    public ExplorerDisplayState display(String playerKey) {
        ExplorerDisplayState state = displays.get(playerKey);
        return state == null ? ExplorerDisplayState.defaults() : state.copy();
    }

    /**
     * 保存全局显示设置；如果与内置默认值一致就删掉记录，避免配置里堆无意义的条目。
     */
    public void setDisplay(String playerKey, ExplorerDisplayState state) {
        if (playerKey == null || state == null) {
            return;
        }
        ExplorerDisplayState copy = state.copy();
        copy.normalize();
        if (copy.equals(ExplorerDisplayState.defaults())) {
            displays.remove(playerKey);
        } else {
            displays.put(playerKey, copy);
        }
    }

    /** 某玩家的默认视图模板。 */
    public ExplorerViewState defaultView(String playerKey) {
        ExplorerViewState state = defaults.get(playerKey);
        return state == null ? ExplorerViewState.defaults() : state.copy();
    }

    /** 保存默认视图模板。 */
    public void setDefaultView(String playerKey, ExplorerViewState state) {
        if (playerKey == null || state == null) {
            return;
        }
        ExplorerViewState copy = state.copy();
        copy.normalize();
        defaults.put(playerKey, copy);
        // 与该玩家默认模板完全一致的文件夹不再需要独立记录，之后修改模板时它们会一起跟随。
        Map<String, Map<String, ExplorerViewState>> byDisk = folders.get(playerKey);
        if (byDisk == null) {
            return;
        }
        for (Map.Entry<String, Map<String, ExplorerViewState>> disk : byDisk.entrySet()) {
            Map<String, ExplorerViewState> map = disk.getValue();
            map.entrySet().removeIf(entry -> entry.getValue().equals(copy));
        }
        byDisk.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (byDisk.isEmpty()) {
            folders.remove(playerKey);
        }
    }

    /** 某玩家在某磁盘上某文件夹最终生效的视图：有覆盖用覆盖，否则用该玩家的默认模板。 */
    public ExplorerViewState view(String playerKey, String diskKey, String folderKey) {
        ExplorerViewState state = override(playerKey, diskKey, folderKey);
        return state == null ? defaultView(playerKey) : state.copy();
    }

    /** 该文件夹是否保存过独立设置。 */
    public boolean hasOverride(String playerKey, String diskKey, String folderKey) {
        return override(playerKey, diskKey, folderKey) != null;
    }

    /**
     * 保存文件夹视图；如果它恰好等于该玩家的默认模板，就删掉覆盖，
     * 这样以后修改默认模板时这个文件夹也会跟着变。
     */
    public void setView(String playerKey, String diskKey, String folderKey, ExplorerViewState state) {
        if (playerKey == null || diskKey == null || diskKey.isBlank()
                || folderKey == null || folderKey.isBlank() || state == null) {
            return;
        }
        ExplorerViewState copy = state.copy();
        copy.normalize();
        if (copy.equals(defaultView(playerKey))) {
            removeOverride(playerKey, diskKey, folderKey);
            return;
        }
        folders.computeIfAbsent(playerKey, key -> new LinkedHashMap<>())
                .computeIfAbsent(diskKey, key -> new LinkedHashMap<>())
                .put(folderKey, copy);
    }

    /** 恢复某文件夹的默认视图（删除覆盖）。 */
    public boolean resetView(String playerKey, String diskKey, String folderKey) {
        return removeOverride(playerKey, diskKey, folderKey);
    }

    /** 清除某玩家在某磁盘上所有文件夹的独立设置。 */
    public boolean resetAllViews(String playerKey, String diskKey) {
        Map<String, Map<String, ExplorerViewState>> byDisk = folders.get(playerKey);
        if (byDisk == null) {
            return false;
        }
        Map<String, ExplorerViewState> removed = byDisk.remove(diskKey);
        if (byDisk.isEmpty()) {
            folders.remove(playerKey);
        }
        return removed != null && !removed.isEmpty();
    }

    /**
     * 丢弃某玩家在某磁盘上已经不存在的文件夹记录（例如文件夹被删除）。
     *
     * @return 是否有记录被移除
     */
    public boolean pruneFolders(String playerKey, String diskKey, Predicate<String> exists) {
        Map<String, Map<String, ExplorerViewState>> byDisk = folders.get(playerKey);
        if (byDisk == null) {
            return false;
        }
        Map<String, ExplorerViewState> map = byDisk.get(diskKey);
        if (map == null || map.isEmpty()) {
            return false;
        }
        boolean changed = map.entrySet().removeIf(entry -> !exists.test(entry.getKey()));
        if (map.isEmpty()) {
            byDisk.remove(diskKey);
        }
        if (byDisk.isEmpty()) {
            folders.remove(playerKey);
        }
        return changed;
    }

    /** 序列化成配置行列表。 */
    public List<String> encode() {
        List<String> lines = new ArrayList<>(displays.size() + defaults.size() + 4);
        for (Map.Entry<String, ExplorerDisplayState> entry : displays.entrySet()) {
            lines.add(TAG_DISPLAY + FIELD_SEP + entry.getKey() + FIELD_SEP + entry.getValue().encode());
        }
        for (Map.Entry<String, ExplorerViewState> entry : defaults.entrySet()) {
            lines.add(TAG_DEFAULT + FIELD_SEP + entry.getKey() + FIELD_SEP + entry.getValue().encode());
        }
        for (Map.Entry<String, Map<String, Map<String, ExplorerViewState>>> byPlayer : folders.entrySet()) {
            for (Map.Entry<String, Map<String, ExplorerViewState>> byDisk : byPlayer.getValue().entrySet()) {
                for (Map.Entry<String, ExplorerViewState> entry : byDisk.getValue().entrySet()) {
                    lines.add(TAG_FOLDER + FIELD_SEP + byPlayer.getKey() + FIELD_SEP
                            + byDisk.getKey() + FIELD_SEP + entry.getKey() + FIELD_SEP + entry.getValue().encode());
                }
            }
        }
        return lines;
    }

    /** 从配置行列表恢复；无法识别的行直接跳过。 */
    public static ExplorerViewStore decode(List<? extends String> lines) {
        ExplorerViewStore store = new ExplorerViewStore();
        if (lines == null) {
            return store;
        }
        List<String> folderLines = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                // 先读默认模板，再读文件夹覆盖：覆盖是否“等于默认”取决于模板已经就位。
                if (line.startsWith(TAG_FOLDER + FIELD_SEP)) {
                    folderLines.add(line);
                } else {
                    decodeLine(store, line);
                }
            } catch (RuntimeException malformed) {
                // 单行损坏不应让整份配置失效。
            }
        }
        for (String line : folderLines) {
            try {
                decodeLine(store, line);
            } catch (RuntimeException malformed) {
                // 单行损坏不应让整份配置失效。
            }
        }
        return store;
    }

    private static void decodeLine(ExplorerViewStore store, String line) {
        int tagEnd = line.indexOf(FIELD_SEP);
        if (tagEnd <= 0 || tagEnd + 1 >= line.length()) {
            return;
        }
        String tag = line.substring(0, tagEnd);
        String rest = line.substring(tagEnd + 1);
        if (TAG_FOLDER.equals(tag)) {
            String[] parts = rest.split("\\" + FIELD_SEP, 4);
            if (parts.length >= 4 && !parts[0].isBlank() && !parts[1].isBlank() && !parts[2].isBlank()) {
                store.setView(parts[0], parts[1], parts[2], ExplorerViewState.decode(parts[3]));
            }
            return;
        }
        String[] parts = rest.split("\\" + FIELD_SEP, 2);
        if (parts.length < 2 || parts[0].isBlank()) {
            return;
        }
        switch (tag) {
            case TAG_DISPLAY -> store.setDisplay(parts[0], ExplorerDisplayState.decode(parts[1]));
            case TAG_DEFAULT -> store.setDefaultView(parts[0], ExplorerViewState.decode(parts[1]));
            default -> {
                // 未知标记属于未来版本或手工编辑，忽略。
            }
        }
    }

    private ExplorerViewState override(String playerKey, String diskKey, String folderKey) {
        if (playerKey == null || diskKey == null || folderKey == null) {
            return null;
        }
        Map<String, Map<String, ExplorerViewState>> byDisk = folders.get(playerKey);
        if (byDisk == null) {
            return null;
        }
        Map<String, ExplorerViewState> map = byDisk.get(diskKey);
        return map == null ? null : map.get(folderKey);
    }

    private boolean removeOverride(String playerKey, String diskKey, String folderKey) {
        if (playerKey == null || diskKey == null || folderKey == null) {
            return false;
        }
        Map<String, Map<String, ExplorerViewState>> byDisk = folders.get(playerKey);
        if (byDisk == null) {
            return false;
        }
        Map<String, ExplorerViewState> map = byDisk.get(diskKey);
        if (map == null) {
            return false;
        }
        boolean removed = map.remove(folderKey) != null;
        if (map.isEmpty()) {
            byDisk.remove(diskKey);
        }
        if (byDisk.isEmpty()) {
            folders.remove(playerKey);
        }
        return removed;
    }
}
