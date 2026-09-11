package cc.sighs.informationera.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cc.sighs.informationera.client.FileExplorerText.t;

/**
 * 纯内存的虚拟文件系统。只用于演示“文件资源管理器”界面，
 * 不读写任何本地真实文件。支持剪切/复制/粘贴/删除/新建/撤销所需的变更。
 */
public final class VirtualFileSystem {

    /** 一个虚拟条目（文件夹或文件）。 */
    public static final class Entry {
        public final String name;
        public final boolean folder;
        public final long size;
        public final String modified;
        public final String created;
        public final String type;
        public final String author;
        /** 仅文件夹有意义：进入该文件夹所用的 key。文件为 null。 */
        public final String childKey;
        /**
         * 快捷方式目标：打开快捷方式时应导航到的文件夹 key。
         * 普通条目为 null；文件夹快捷方式 = 目标文件夹 childKey；文件快捷方式 = 文件所在文件夹 key。
         */
        public final String shortcutTargetKey;

        private Entry(String name, boolean folder, long size, String modified, String created,
                      String type, String author, String childKey, String shortcutTargetKey) {
            this.name = name;
            this.folder = folder;
            this.size = size;
            this.modified = modified;
            this.created = created;
            this.type = type;
            this.author = author;
            this.childKey = childKey;
            this.shortcutTargetKey = shortcutTargetKey;
        }
    }

    /** 一个虚拟文件夹。name/parentKey 在重命名/移动时会被更新。 */
    public static final class Folder {
        public final String key;
        public String name;
        public String parentKey;
        public final List<Entry> entries;

        private Folder(String key, String name, String parentKey, List<Entry> entries) {
            this.key = key;
            this.name = name;
            this.parentKey = parentKey;
            this.entries = entries;
        }
    }

    private final Map<String, Folder> folders = new LinkedHashMap<>();
    /** 回收站登记表：id -> 被删除条目及其原位置/子树。 */
    private final Map<String, RecycleEntry> recycleEntries = new LinkedHashMap<>();
    private int nextId = 0;

    /** 回收站中的一条记录。 */
    public static final class RecycleEntry {
        public final String id;
        public final String folderKey;
        public final Entry entry;
        public final Map<String, Folder> subtree;
        public final long deletedAt;

        private RecycleEntry(String id, String folderKey, Entry entry, Map<String, Folder> subtree, long deletedAt) {
            this.id = id;
            this.folderKey = folderKey;
            this.entry = entry;
            this.subtree = subtree;
            this.deletedAt = deletedAt;
        }
    }

    /** 当前回收站内容（按删除时间倒序）。 */
    public List<RecycleEntry> recycleEntries() {
        List<RecycleEntry> result = new ArrayList<>(recycleEntries.values());
        result.sort((a, b) -> Long.compare(b.deletedAt, a.deletedAt));
        return result;
    }

    /** 判断某目录下是否存在指定名称的条目。 */
    public boolean hasName(String folderKey, String name) {
        return contains(folderKey, name);
    }

    public Folder folder(String key) {
        return folders.get(key);
    }

    /** 该文件夹下是否还包含子文件夹（用于显示展开箭头）。 */
    public boolean hasChildFolders(String key) {
        Folder folder = folders.get(key);
        if (folder == null) {
            return false;
        }
        for (Entry entry : folder.entries) {
            if (entry.folder && entry.childKey != null) {
                return true;
            }
        }
        return false;
    }

    /** 该文件夹下的直接子文件夹（展开用）。 */
    public List<Entry> childFolders(String key) {
        List<Entry> result = new ArrayList<>();
        Folder folder = folders.get(key);
        if (folder == null) {
            return result;
        }
        for (Entry entry : folder.entries) {
            if (entry.folder && entry.childKey != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /** 根据输入（key 或显示名）解析出文件夹 key，找不到返回 null。 */
    public String resolve(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (folders.containsKey(trimmed)) {
            return trimmed;
        }
        for (Folder folder : folders.values()) {
            if (folder.name.equalsIgnoreCase(trimmed) || folder.key.equalsIgnoreCase(trimmed)) {
                return folder.key;
            }
        }
        return null;
    }

    /** 自然排序：按数字段/非数字段切分，数字段按数值比较（忽略前导零），非数字段忽略大小写。 */
    public static int naturalCompare(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        int ia = 0;
        int ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia);
            char cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ja = ia;
                while (ja < a.length() && Character.isDigit(a.charAt(ja))) {
                    ja++;
                }
                int jb = ib;
                while (jb < b.length() && Character.isDigit(b.charAt(jb))) {
                    jb++;
                }
                int cmp = compareNumeric(a.substring(ia, ja), b.substring(ib, jb));
                if (cmp != 0) {
                    return cmp;
                }
                ia = ja;
                ib = jb;
            } else {
                char ua = Character.toUpperCase(ca);
                char ub = Character.toUpperCase(cb);
                if (ua != ub) {
                    return ua - ub;
                }
                ia++;
                ib++;
            }
        }
        return (a.length() - ia) - (b.length() - ib);
    }

    private static int compareNumeric(String a, String b) {
        int sa = 0;
        int sb = 0;
        while (sa < a.length() - 1 && a.charAt(sa) == '0') {
            sa++;
        }
        while (sb < b.length() - 1 && b.charAt(sb) == '0') {
            sb++;
        }
        String ta = a.substring(sa);
        String tb = b.substring(sb);
        if (ta.length() != tb.length()) {
            return ta.length() - tb.length();
        }
        return ta.compareTo(tb);
    }

    // ---------- 变更操作（返回撤销动作） ----------

    public boolean contains(String folderKey, String name) {
        Folder folder = folders.get(folderKey);
        if (folder == null) {
            return false;
        }
        for (Entry entry : folder.entries) {
            if (entry.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 剪切粘贴：把 entry 从 sourceKey 移动到 targetKey。返回撤销动作。 */
    public Runnable move(String sourceKey, String targetKey, Entry entry) {
        Folder source = folders.get(sourceKey);
        Folder target = folders.get(targetKey);
        if (source == null || target == null) {
            return () -> {
            };
        }
        if (!source.entries.remove(entry)) {
            return () -> {
            };
        }
        target.entries.add(entry);
        if (entry.folder && entry.childKey != null) {
            Folder child = folders.get(entry.childKey);
            if (child != null) {
                child.parentKey = targetKey;
            }
        }
        return () -> {
            target.entries.remove(entry);
            source.entries.add(entry);
            if (entry.folder && entry.childKey != null) {
                Folder child = folders.get(entry.childKey);
                if (child != null) {
                    child.parentKey = sourceKey;
                }
            }
        };
    }

    /** 复制粘贴：把 entry 复制到 targetKey（文件夹深度复制）。返回撤销动作。 */
    public Runnable duplicateInto(String targetKey, Entry entry) {
        Folder target = folders.get(targetKey);
        if (target == null) {
            return () -> {
            };
        }
        String newName = uniqueCopyName(targetKey, entry.name);
        Entry duplicate;
        if (entry.folder && entry.childKey != null) {
            String newKey = copyFolder(entry.childKey, targetKey, newName);
            duplicate = new Entry(newName, true, -1L, entry.modified, entry.created, entry.type,
                    entry.author, newKey, entry.shortcutTargetKey);
        } else {
            duplicate = new Entry(newName, false, entry.size, entry.modified, entry.created, entry.type,
                    entry.author, null, entry.shortcutTargetKey);
        }
        target.entries.add(duplicate);
        return () -> {
            target.entries.remove(duplicate);
            if (duplicate.folder && duplicate.childKey != null) {
                collectSubtree(duplicate.childKey, new LinkedHashMap<>());
            }
        };
    }

    /** 新建文件夹。返回撤销动作。 */
    public Runnable createFolder(String parentKey, String baseName) {
        Folder parent = folders.get(parentKey);
        if (parent == null) {
            return () -> {
            };
        }
        String name = uniqueName(parentKey, baseName);
        String key = generateKey(parentKey);
        Folder folder = new Folder(key, name, parentKey, new ArrayList<>());
        folders.put(key, folder);
        Entry entry = new Entry(name, true, -1L, t("informationera.fe.just_now"), t("informationera.fe.just_now"),
                t("informationera.fe.folder_type"), "sighs", key, null);
        parent.entries.add(entry);
        return () -> {
            parent.entries.remove(entry);
            folders.remove(key);
        };
    }

    /**
     * 新建快捷方式。parentKey 为快捷方式所在文件夹，targetSourceKey 为目标条目所在文件夹。
     * 目标为文件夹时快捷方式指向该文件夹；目标为文件时指向其所在文件夹（打开所在位置）。
     */
    public Runnable createShortcut(String parentKey, String targetSourceKey, Entry target) {
        Folder parent = folders.get(parentKey);
        if (parent == null) {
            return () -> {
            };
        }
        String base = target.name + " - " + t("informationera.fe.shortcut_suffix") + ".lnk";
        String name = uniqueName(parentKey, base);
        String shortcutTargetKey = (target.folder && target.childKey != null) ? target.childKey : targetSourceKey;
        Entry shortcut = new Entry(name, false, 1024L, t("informationera.fe.just_now"), t("informationera.fe.just_now"),
                t("informationera.fe.shortcut_type"), "sighs", null, shortcutTargetKey);
        parent.entries.add(shortcut);
        return () -> parent.entries.remove(shortcut);
    }

    /** 删除条目（文件夹会连同子树一并移除）。返回撤销动作。 */
    public Runnable delete(String folderKey, Entry entry) {
        Folder parent = folders.get(folderKey);
        if (parent == null) {
            return () -> {
            };
        }
        if (!parent.entries.remove(entry)) {
            return () -> {
            };
        }
        Map<String, Folder> removed = new LinkedHashMap<>();
        if (entry.folder && entry.childKey != null) {
            collectSubtree(entry.childKey, removed);
        }
        return () -> {
            parent.entries.add(entry);
            folders.putAll(removed);
        };
    }

    /**
     * 把条目（含子树）移到回收站。成功返回撤销动作，失败（找不到父目录）返回 null。
     * 回收站采用“真实 Folder”方案：entry 原样放进 recyclebin 的 entries，同时登记原位置与子树。
     */
    public Runnable moveToRecycle(String folderKey, Entry entry) {
        Folder parent = folders.get(folderKey);
        Folder recycle = folders.get("recyclebin");
        if (parent == null || recycle == null) {
            return null;
        }
        if (!parent.entries.remove(entry)) {
            return null;
        }
        // 回收站内不允许同名条目：若已存在同名，则改名（如“旧文件 (2)”）。
        Entry entryToMove = entry;
        String name = entryToMove.name;
        if (contains("recyclebin", name)) {
            name = uniqueName("recyclebin", name);
            Entry renamed = new Entry(name, entryToMove.folder, entryToMove.size, entryToMove.modified,
                    entryToMove.created, entryToMove.type, entryToMove.author,
                    entryToMove.childKey, entryToMove.shortcutTargetKey);
            if (entryToMove.folder && entryToMove.childKey != null) {
                Folder child = folders.get(entryToMove.childKey);
                if (child != null) {
                    child.name = name;
                }
            }
            entryToMove = renamed;
        }
        Map<String, Folder> removed = new LinkedHashMap<>();
        if (entryToMove.folder && entryToMove.childKey != null) {
            collectSubtree(entryToMove.childKey, removed);
        }
        String id = "r" + (++nextId);
        long now = System.currentTimeMillis();
        RecycleEntry record = new RecycleEntry(id, folderKey, entryToMove, removed, now);
        recycleEntries.put(id, record);
        recycle.entries.add(entryToMove);
        if (entryToMove.folder && entryToMove.childKey != null) {
            Folder child = folders.get(entryToMove.childKey);
            if (child != null) {
                child.parentKey = "recyclebin";
            }
        }
        final Entry movedEntry = entryToMove;
        return () -> {
            recycle.entries.remove(movedEntry);
            recycleEntries.remove(id);
            parent.entries.add(movedEntry);
            if (movedEntry.folder && movedEntry.childKey != null) {
                Folder child = folders.get(movedEntry.childKey);
                if (child != null) {
                    child.parentKey = folderKey;
                }
            }
            folders.putAll(removed);
        };
    }

    /** 还原回收站中的一条记录。成功返回撤销动作（撤销 = 重新放回回收站）。 */
    public Runnable restoreFromRecycle(String id) {
        RecycleEntry record = recycleEntries.get(id);
        if (record == null) {
            return null;
        }
        Folder recycle = folders.get("recyclebin");
        Folder target = folders.get(record.folderKey);
        if (recycle == null || target == null || !recycle.entries.remove(record.entry)) {
            return null;
        }
        // 原位置若有同名，则改名。
        RecycleEntry recordToRestore = record;
        String name = recordToRestore.entry.name;
        if (contains(recordToRestore.folderKey, name)) {
            name = uniqueName(recordToRestore.folderKey, name);
            Entry renamed = new Entry(name, recordToRestore.entry.folder, recordToRestore.entry.size,
                    recordToRestore.entry.modified, recordToRestore.entry.created, recordToRestore.entry.type,
                    recordToRestore.entry.author, recordToRestore.entry.childKey, recordToRestore.entry.shortcutTargetKey);
            if (recordToRestore.entry.folder && recordToRestore.entry.childKey != null) {
                Folder child = folders.get(recordToRestore.entry.childKey);
                if (child != null) {
                    child.name = name;
                }
            }
            recordToRestore = new RecycleEntry(id, recordToRestore.folderKey, renamed,
                    recordToRestore.subtree, recordToRestore.deletedAt);
            recycleEntries.put(id, recordToRestore);
        }
        // 子树要放回 folders。
        for (Folder folder : recordToRestore.subtree.values()) {
            folders.put(folder.key, folder);
        }
        target.entries.add(recordToRestore.entry);
        if (recordToRestore.entry.folder && recordToRestore.entry.childKey != null) {
            Folder child = folders.get(recordToRestore.entry.childKey);
            if (child != null) {
                child.parentKey = recordToRestore.folderKey;
            }
        }
        recycleEntries.remove(id);
        final RecycleEntry restoredRecord = recordToRestore;
        return () -> {
            target.entries.remove(restoredRecord.entry);
            Map<String, Folder> removed = new LinkedHashMap<>();
            if (restoredRecord.entry.folder && restoredRecord.entry.childKey != null) {
                collectSubtree(restoredRecord.entry.childKey, removed);
            }
            recycle.entries.add(restoredRecord.entry);
            if (restoredRecord.entry.folder && restoredRecord.entry.childKey != null) {
                Folder child = folders.get(restoredRecord.entry.childKey);
                if (child != null) {
                    child.parentKey = "recyclebin";
                }
            }
            recycleEntries.put(id, restoredRecord);
        };
    }

    /** 还原回收站中全部记录。返回撤销动作（撤销 = 全部重新放回回收站）。 */
    public Runnable restoreAllFromRecycle() {
        List<RecycleEntry> all = recycleEntries();
        List<Runnable> undos = new ArrayList<>();
        for (RecycleEntry record : all) {
            Runnable undo = restoreFromRecycle(record.id);
            if (undo != null) {
                undos.add(undo);
            }
        }
        if (undos.isEmpty()) {
            return () -> {
            };
        }
        return () -> {
            for (int i = undos.size() - 1; i >= 0; i--) {
                undos.get(i).run();
            }
        };
    }

    /** 永久清空回收站（丢弃登记并移除子树）。返回撤销动作（撤销 = 全部放回回收站）。 */
    public Runnable emptyRecycle() {
        List<RecycleEntry> all = recycleEntries();
        if (all.isEmpty()) {
            return () -> {
            };
        }
        Folder recycle = folders.get("recyclebin");
        List<Runnable> restores = new ArrayList<>();
        for (RecycleEntry record : all) {
            if (recycle != null) {
                recycle.entries.remove(record.entry);
            }
            recycleEntries.remove(record.id);
            // 子树已经不在 folders 里（移入回收站时被 collectSubtree 移出），永久丢弃即可；
            // 但为可撤销，恢复时需放回子树并重建记录。
            restores.add(() -> {
                recycleEntries.put(record.id, record);
                for (Folder folder : record.subtree.values()) {
                    folders.put(folder.key, folder);
                }
                if (recycle != null) {
                    recycle.entries.add(record.entry);
                }
                if (record.entry.folder && record.entry.childKey != null) {
                    Folder child = folders.get(record.entry.childKey);
                    if (child != null) {
                        child.parentKey = "recyclebin";
                    }
                }
            });
        }
        return () -> {
            for (int i = restores.size() - 1; i >= 0; i--) {
                restores.get(i).run();
            }
        };
    }

    /** 重命名条目（文件夹会同步更新其名称）。成功返回撤销动作，失败返回 null。 */
    public Runnable rename(String folderKey, Entry entry, String newName) {
        Folder parent = folders.get(folderKey);
        if (parent == null) {
            return null;
        }
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty() || trimmed.equals(entry.name) || contains(folderKey, trimmed)) {
            return null;
        }
        int index = parent.entries.indexOf(entry);
        if (index < 0) {
            return null;
        }
        Entry oldEntry = entry;
        Entry newEntry = new Entry(trimmed, entry.folder, entry.size, entry.modified, entry.created,
                entry.type, entry.author, entry.childKey, entry.shortcutTargetKey);
        parent.entries.set(index, newEntry);
        final String[] oldFolderName = new String[]{null};
        if (entry.folder && entry.childKey != null) {
            Folder child = folders.get(entry.childKey);
            if (child != null) {
                oldFolderName[0] = child.name;
                child.name = trimmed;
            }
        }
        return () -> {
            parent.entries.set(index, oldEntry);
            if (oldFolderName[0] != null && entry.childKey != null) {
                Folder child = folders.get(entry.childKey);
                if (child != null) {
                    child.name = oldFolderName[0];
                }
            }
        };
    }

    // ---------- 内部辅助 ----------

    private String uniqueName(String folderKey, String base) {
        if (!contains(folderKey, base)) {
            return base;
        }
        int n = 2;
        while (contains(folderKey, base + " (" + n + ")")) {
            n++;
        }
        return base + " (" + n + ")";
    }

    /** 复制/粘贴副本命名：xxx - 副本、xxx - 副本 (2)、xxx - 副本 (3) ... */
    private String uniqueCopyName(String folderKey, String base) {
        String copySuffix = " - " + t("informationera.fe.copy_suffix");
        String candidate = base + copySuffix;
        if (!contains(folderKey, candidate)) {
            return candidate;
        }
        int n = 2;
        while (contains(folderKey, base + copySuffix + " (" + n + ")")) {
            n++;
        }
        return base + copySuffix + " (" + n + ")";
    }

    /**
     * 用 sourceEntry 覆盖 targetKey 下同名条目：删除目标后写入，返回撤销动作。
     * 目标不存在同名时等价于普通复制/移动后的写入逻辑由调用方处理，这里只在确有同名时使用。
     * 返回 null 表示目标文件夹不存在或未找到同名条目。
     */
    public Runnable replace(String targetKey, Entry sourceEntry, boolean copy, String sourceKey) {
        Folder target = folders.get(targetKey);
        if (target == null) {
            return null;
        }
        Entry existing = null;
        for (Entry e : target.entries) {
            if (e.name.equals(sourceEntry.name)) {
                existing = e;
                break;
            }
        }
        if (existing == null) {
            return null;
        }
        final Entry existingEntry = existing;
        Map<String, Folder> removed = new LinkedHashMap<>();
        if (existingEntry.folder && existingEntry.childKey != null) {
            collectSubtree(existingEntry.childKey, removed);
        }
        target.entries.remove(existingEntry);

        Entry written;
        if (copy) {
            if (sourceEntry.folder && sourceEntry.childKey != null) {
                String newKey = copyFolder(sourceEntry.childKey, targetKey, sourceEntry.name);
                written = new Entry(sourceEntry.name, true, -1L, sourceEntry.modified, sourceEntry.created,
                        sourceEntry.type, sourceEntry.author, newKey, sourceEntry.shortcutTargetKey);
            } else {
                written = new Entry(sourceEntry.name, false, sourceEntry.size, sourceEntry.modified,
                        sourceEntry.created, sourceEntry.type, sourceEntry.author, null, sourceEntry.shortcutTargetKey);
            }
            target.entries.add(written);
            return () -> {
                target.entries.remove(written);
                if (written.folder && written.childKey != null) {
                    collectSubtree(written.childKey, new LinkedHashMap<>());
                }
                target.entries.add(existingEntry);
                folders.putAll(removed);
            };
        } else {
            Folder source = folders.get(sourceKey);
            if (source == null || !source.entries.remove(sourceEntry)) {
                target.entries.add(existingEntry);
                folders.putAll(removed);
                return () -> {
                };
            }
            target.entries.add(sourceEntry);
            if (sourceEntry.folder && sourceEntry.childKey != null) {
                Folder child = folders.get(sourceEntry.childKey);
                if (child != null) {
                    child.parentKey = targetKey;
                }
            }
            return () -> {
                target.entries.remove(sourceEntry);
                source.entries.add(sourceEntry);
                if (sourceEntry.folder && sourceEntry.childKey != null) {
                    Folder child = folders.get(sourceEntry.childKey);
                    if (child != null) {
                        child.parentKey = sourceKey;
                    }
                }
                target.entries.add(existingEntry);
                folders.putAll(removed);
            };
        }
    }

    private String generateKey(String parentKey) {
        String key;
        do {
            key = parentKey + "/_new_" + (++nextId);
        } while (folders.containsKey(key));
        return key;
    }

    /** 深度复制文件夹子树，返回新文件夹的 key。 */
    private String copyFolder(String sourceKey, String targetParentKey, String newName) {
        Folder source = folders.get(sourceKey);
        if (source == null) {
            return null;
        }
        String newKey = generateKey(targetParentKey);
        Folder copy = new Folder(newKey, newName, targetParentKey, new ArrayList<>());
        folders.put(newKey, copy);
        for (Entry entry : source.entries) {
            if (entry.folder && entry.childKey != null) {
                String childKey = copyFolder(entry.childKey, newKey, entry.name);
                copy.entries.add(new Entry(entry.name, true, -1L, entry.modified, entry.created,
                        entry.type, entry.author, childKey, entry.shortcutTargetKey));
            } else {
                copy.entries.add(new Entry(entry.name, false, entry.size, entry.modified, entry.created,
                        entry.type, entry.author, null, entry.shortcutTargetKey));
            }
        }
        return newKey;
    }

    /** 把 key 及其所有后代从 folders 中移入 out（供删除/撤销删除用）。 */
    private void collectSubtree(String key, Map<String, Folder> out) {
        Folder folder = folders.remove(key);
        if (folder == null) {
            return;
        }
        out.put(key, folder);
        for (Entry entry : folder.entries) {
            if (entry.folder && entry.childKey != null) {
                collectSubtree(entry.childKey, out);
            }
        }
    }

    public static VirtualFileSystem create() {
        VirtualFileSystem fs = new VirtualFileSystem();
        fs.add(new Folder("home", "主文件夹", null, list(
                folder("桌面", "desktop"),
                folder("下载", "downloads"),
                folder("文档", "documents"),
                folder("图片", "pictures"),
                folder("音乐", "music"),
                folder("视频", "videos"),
                file("快速访问说明.txt", 1_248, "2024/6/1 09:20", t("informationera.fe.type_text")),
                file("项目规划.docx", 48_112, "2024/6/11 18:44", t("informationera.fe.type_word")),
                file("新建文本文档.txt", 0, "2024/6/12 07:55", t("informationera.fe.type_text"))
        )));

        fs.add(new Folder("desktop", "桌面", "home", list(
                folder("工作", "desktop/work"),
                file("截图.png", 2_118_332, "2024/6/10 22:05", t("informationera.fe.type_png")),
                file("记事本.txt", 2_304, "2024/6/9 16:12", t("informationera.fe.type_text")),
                file("启动器.lnk", 1_204, "2024/6/8 11:30", t("informationera.fe.shortcut_type"))
        )));

        fs.add(new Folder("desktop/work", "工作", "desktop", list(
                folder("资料", "desktop/work/materials"),
                file("周报.docx", 26_140, "2024/6/11 18:40", t("informationera.fe.type_word")),
                file("数据表.xlsx", 64_512, "2024/6/10 15:02", t("informationera.fe.type_excel"))
        )));

        fs.add(new Folder("desktop/work/materials", "资料", "desktop/work", list(
                file("说明文档.pdf", 1_512_960, "2024/6/5 10:20", t("informationera.fe.type_pdf")),
                file("模板.zip", 5_228_600, "2024/6/4 14:11", t("informationera.fe.type_zip"))
        )));

        fs.add(new Folder("downloads", "下载", "home", list(
                file("apricityui-1.2.4.jar", 5_315_260, "2024/6/12 13:08", t("informationera.fe.type_jar")),
                file("安装包.exe", 94_371_840, "2024/6/7 20:31", t("informationera.fe.type_app")),
                file("壁纸.jpg", 4_102_340, "2024/6/6 09:15", t("informationera.fe.type_jpg"))
        )));

        fs.add(new Folder("documents", "文档", "home", list(
                folder("稿件", "documents/drafts"),
                file("毕业论文.docx", 1_208_322, "2024/6/3 21:48", t("informationera.fe.type_word")),
                file("备忘.txt", 512, "2024/6/2 08:30", t("informationera.fe.type_text"))
        )));

        fs.add(new Folder("documents/drafts", "稿件", "documents", list(
                file("第一章.txt", 20_480, "2024/6/1 12:00", t("informationera.fe.type_text")),
                file("第二章.txt", 18_432, "2024/6/2 19:20", t("informationera.fe.type_text"))
        )));

        fs.add(new Folder("pictures", "图片", "home", list(
                folder("壁纸", "pictures/wallpapers"),
                file("截图_001.png", 2_204_160, "2024/6/10 22:05", t("informationera.fe.type_png")),
                file("全家福.jpg", 6_820_400, "2024/5/20 13:00", t("informationera.fe.type_jpg"))
        )));

        fs.add(new Folder("pictures/wallpapers", "壁纸", "pictures", list(
                file("山景.png", 3_145_728, "2024/5/18 08:00", t("informationera.fe.type_png")),
                file("星空.png", 2_621_440, "2024/5/17 08:00", t("informationera.fe.type_png"))
        )));

        fs.add(new Folder("music", "音乐", "home", list(
                file("片头曲.mp3", 8_912_896, "2024/5/25 20:00", t("informationera.fe.type_mp3")),
                file("背景音乐.mp3", 12_582_912, "2024/5/24 20:00", t("informationera.fe.type_mp3"))
        )));

        fs.add(new Folder("videos", "视频", "home", list(
                file("演示.mp4", 214_748_364, "2024/6/1 10:00", t("informationera.fe.type_mp4")),
                file("录屏.mp4", 96_468_992, "2024/5/30 17:00", t("informationera.fe.type_mp4"))
        )));

        // OneDrive：导航窗格独立节点（无父级）。
        fs.add(new Folder("onedrive", "OneDrive", null, list(
                folder("文档", "onedrive/documents"),
                file("共享链接.txt", 1_024, "2024/6/1 09:00", t("informationera.fe.type_text"))
        )));
        fs.add(new Folder("onedrive/documents", "文档", "onedrive", list(
                file("云端笔记.docx", 32_768, "2024/6/5 14:20", t("informationera.fe.type_word"))
        )));

        fs.add(new Folder("thispc", "此电脑", null, list(
                drive("本地磁盘 (C:)", "thispc/C"),
                drive("本地磁盘 (D:)", "thispc/D"),
                drive("可移动磁盘 (E:)", "thispc/E")
        )));

        fs.add(new Folder("thispc/C", "本地磁盘 (C:)", "thispc", list(
                folder("Windows", "thispc/C/windows"),
                folder("Program Files", "thispc/C/program-files"),
                folder("用户", "thispc/C/users"),
                file("pagefile.sys", 4_294_967_296L, "2024/6/12 14:00", t("informationera.fe.type_system"))
        )));

        fs.add(new Folder("thispc/C/windows", "Windows", "thispc/C", list(
                folder("System32", "thispc/C/windows/system32"),
                folder("Temp", "thispc/C/windows/temp"),
                file("win.ini", 392, "2024/1/1 00:00", t("informationera.fe.type_config"))
        )));

        fs.add(new Folder("thispc/C/windows/system32", "System32", "thispc/C/windows", list(
                file("kernel32.dll", 798_720, "2024/1/1 00:00", t("informationera.fe.type_dll"))
        )));

        // 压力测试目录：约 2000 个文件，用于验证虚拟滚动与自然排序。
        fs.add(new Folder("thispc/C/windows/temp", "Temp", "thispc/C/windows", stressFiles(2000)));

        fs.add(new Folder("thispc/C/program-files", "Program Files", "thispc/C", list(
                folder("Information Era", "thispc/C/program-files/information-era"),
                folder("Common Files", "thispc/C/program-files/common-files")
        )));

        fs.add(new Folder("thispc/C/program-files/information-era", "Information Era", "thispc/C/program-files", list(
                file("informationera.jar", 12_456_960, "2024/6/12 13:08", t("informationera.fe.type_jar"))
        )));

        fs.add(new Folder("thispc/C/program-files/common-files", "Common Files", "thispc/C/program-files", list(
                file("readme.txt", 1_024, "2024/1/1 00:00", t("informationera.fe.type_text"))
        )));

        fs.add(new Folder("thispc/C/users", "用户", "thispc/C", list(
                folder("Public", "thispc/C/users/public"),
                folder("sighs", "thispc/C/users/sighs")
        )));

        fs.add(new Folder("thispc/C/users/public", "Public", "thispc/C/users", list(
                file("desktop.ini", 220, "2024/1/1 00:00", t("informationera.fe.type_config"))
        )));

        fs.add(new Folder("thispc/C/users/sighs", "sighs", "thispc/C/users", list(
                folder("Downloads", "thispc/C/users/sighs/downloads"),
                file("desktop.ini", 220, "2024/1/1 00:00", t("informationera.fe.type_config"))
        )));

        fs.add(new Folder("thispc/C/users/sighs/downloads", "Downloads", "thispc/C/users/sighs", list()));

        fs.add(new Folder("thispc/D", "本地磁盘 (D:)", "thispc", list(
                folder("Games", "thispc/D/games"),
                folder("Work", "thispc/D/work"),
                file("备份.zip", 1_048_576_000L, "2024/6/1 02:00", t("informationera.fe.type_zip"))
        )));

        fs.add(new Folder("thispc/D/games", "Games", "thispc/D", list(
                file("minecraft.exe", 42_150_400, "2024/5/30 18:00", t("informationera.fe.type_app"))
        )));

        fs.add(new Folder("thispc/D/work", "Work", "thispc/D", list(
                folder("ProjectA", "thispc/D/work/project-a")
        )));

        fs.add(new Folder("thispc/D/work/project-a", "ProjectA", "thispc/D/work", list(
                file("main.java", 18_240, "2024/6/11 23:00", t("informationera.fe.type_java"))
        )));

        fs.add(new Folder("thispc/E", "可移动磁盘 (E:)", "thispc", list(
                file("照片_001.jpg", 3_014_656, "2024/6/11 10:00", t("informationera.fe.type_jpg")),
                file("安装包.msi", 45_320_192, "2024/6/10 12:30", t("informationera.fe.type_app"))
        )));

        fs.add(new Folder("thispc/libraries", "库", null, list(
                folder("文档", "thispc/libraries/documents"),
                folder("图片", "thispc/libraries/pictures"),
                folder("音乐", "thispc/libraries/music"),
                folder("视频", "thispc/libraries/videos")
        )));

        fs.add(new Folder("thispc/libraries/documents", "文档", "thispc/libraries", list()));
        fs.add(new Folder("thispc/libraries/pictures", "图片", "thispc/libraries", list()));
        fs.add(new Folder("thispc/libraries/music", "音乐", "thispc/libraries", list()));
        fs.add(new Folder("thispc/libraries/videos", "视频", "thispc/libraries", list()));

        fs.add(new Folder("thispc/network", "网络", null, list(
                folder("SIGHS-PC", "thispc/network/sighs-pc")
        )));
        fs.add(new Folder("thispc/network/sighs-pc", "SIGHS-PC", "thispc/network", list(
                folder("共享文档", "thispc/network/sighs-pc/shared"),
                file("打印机.pdf", 512_000, "2024/6/1 08:00", t("informationera.fe.type_pdf"))
        )));
        fs.add(new Folder("thispc/network/sighs-pc/shared", "共享文档", "thispc/network/sighs-pc", list()));

        // 回收站是真实 Folder：删除时把条目放进这里，还原时取出。
        fs.add(new Folder("recyclebin", t("informationera.fe.recycle_bin"), null, list()));

        return fs;
    }

    /** 生成压力测试文件列表（file1.txt ... fileN.txt，含 file10 等用于自然排序验证）。 */
    private static List<Entry> stressFiles(int count) {
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            entries.add(file("file" + i + ".txt", 1_024L + (i % 100) * 17L,
                    "2024/6/" + (1 + i % 28) + " " + (i % 24) + ":" + (i % 60), t("informationera.fe.type_text")));
        }
        return entries;
    }

    private void add(Folder folder) {
        folders.put(folder.key, folder);
    }

    private static Entry folder(String name, String childKey) {
        return new Entry(name, true, -1L, "2024/6/12 14:30", "2024/6/12 14:30",
                t("informationera.fe.folder_type"), "sighs", childKey, null);
    }

    private static Entry drive(String name, String childKey) {
        return new Entry(name, true, -1L, "2024/6/12 14:30", "2024/6/12 14:30",
                t("informationera.fe.drive_type"), "sighs", childKey, null);
    }

    private static Entry file(String name, long size, String modified, String type) {
        return new Entry(name, false, size, modified, modified, type, "sighs", null, null);
    }

    @SafeVarargs
    private static List<Entry> list(Entry... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }
}
