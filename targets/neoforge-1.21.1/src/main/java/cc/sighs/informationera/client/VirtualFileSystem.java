package cc.sighs.informationera.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        public final String type;
        /** 仅文件夹有意义：进入该文件夹所用的 key。文件为 null。 */
        public final String childKey;

        private Entry(String name, boolean folder, long size, String modified, String type, String childKey) {
            this.name = name;
            this.folder = folder;
            this.size = size;
            this.modified = modified;
            this.type = type;
            this.childKey = childKey;
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
    private int nextId = 0;

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
        String newName = uniqueName(targetKey, entry.name);
        Entry duplicate;
        if (entry.folder && entry.childKey != null) {
            String newKey = copyFolder(entry.childKey, targetKey, newName);
            duplicate = new Entry(newName, true, -1L, entry.modified, entry.type, newKey);
        } else {
            duplicate = new Entry(newName, false, entry.size, entry.modified, entry.type, null);
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
        Entry entry = new Entry(name, true, -1L, "刚刚", "文件夹", key);
        parent.entries.add(entry);
        return () -> {
            parent.entries.remove(entry);
            folders.remove(key);
        };
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
        Entry newEntry = new Entry(trimmed, entry.folder, entry.size, entry.modified, entry.type, entry.childKey);
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
                copy.entries.add(new Entry(entry.name, true, -1L, entry.modified, entry.type, childKey));
            } else {
                copy.entries.add(new Entry(entry.name, false, entry.size, entry.modified, entry.type, null));
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
                file("快速访问说明.txt", 1_248, "2024/6/1 09:20", "文本文档"),
                file("项目规划.docx", 48_112, "2024/6/11 18:44", "Word 文档"),
                file("新建文本文档.txt", 0, "2024/6/12 07:55", "文本文档")
        )));

        fs.add(new Folder("desktop", "桌面", "home", list(
                folder("工作", "desktop/work"),
                file("截图.png", 2_118_332, "2024/6/10 22:05", "PNG 图片"),
                file("记事本.txt", 2_304, "2024/6/9 16:12", "文本文档"),
                file("启动器.lnk", 1_204, "2024/6/8 11:30", "快捷方式")
        )));

        fs.add(new Folder("desktop/work", "工作", "desktop", list(
                folder("资料", "desktop/work/materials"),
                file("周报.docx", 26_140, "2024/6/11 18:40", "Word 文档"),
                file("数据表.xlsx", 64_512, "2024/6/10 15:02", "Excel 工作表")
        )));

        fs.add(new Folder("desktop/work/materials", "资料", "desktop/work", list(
                file("说明文档.pdf", 1_512_960, "2024/6/5 10:20", "PDF 文档"),
                file("模板.zip", 5_228_600, "2024/6/4 14:11", "压缩文件夹")
        )));

        fs.add(new Folder("downloads", "下载", "home", list(
                file("apricityui-1.2.4.jar", 5_315_260, "2024/6/12 13:08", "JAR 文件"),
                file("安装包.exe", 94_371_840, "2024/6/7 20:31", "应用程序"),
                file("壁纸.jpg", 4_102_340, "2024/6/6 09:15", "JPG 图片")
        )));

        fs.add(new Folder("documents", "文档", "home", list(
                folder("稿件", "documents/drafts"),
                file("毕业论文.docx", 1_208_322, "2024/6/3 21:48", "Word 文档"),
                file("备忘.txt", 512, "2024/6/2 08:30", "文本文档")
        )));

        fs.add(new Folder("documents/drafts", "稿件", "documents", list(
                file("第一章.txt", 20_480, "2024/6/1 12:00", "文本文档"),
                file("第二章.txt", 18_432, "2024/6/2 19:20", "文本文档")
        )));

        fs.add(new Folder("pictures", "图片", "home", list(
                folder("壁纸", "pictures/wallpapers"),
                file("截图_001.png", 2_204_160, "2024/6/10 22:05", "PNG 图片"),
                file("全家福.jpg", 6_820_400, "2024/5/20 13:00", "JPG 图片")
        )));

        fs.add(new Folder("pictures/wallpapers", "壁纸", "pictures", list(
                file("山景.png", 3_145_728, "2024/5/18 08:00", "PNG 图片"),
                file("星空.png", 2_621_440, "2024/5/17 08:00", "PNG 图片")
        )));

        fs.add(new Folder("music", "音乐", "home", list(
                file("片头曲.mp3", 8_912_896, "2024/5/25 20:00", "MP3 音频"),
                file("背景音乐.mp3", 12_582_912, "2024/5/24 20:00", "MP3 音频")
        )));

        fs.add(new Folder("videos", "视频", "home", list(
                file("演示.mp4", 214_748_364, "2024/6/1 10:00", "MP4 视频"),
                file("录屏.mp4", 96_468_992, "2024/5/30 17:00", "MP4 视频")
        )));

        fs.add(new Folder("thispc", "此电脑", null, list(
                drive("本地磁盘 (C:)", "thispc/C"),
                drive("本地磁盘 (D:)", "thispc/D")
        )));

        fs.add(new Folder("thispc/C", "本地磁盘 (C:)", "thispc", list(
                folder("Windows", "thispc/C/windows"),
                folder("Program Files", "thispc/C/program-files"),
                folder("用户", "thispc/C/users"),
                file("pagefile.sys", 4_294_967_296L, "2024/6/12 14:00", "系统文件")
        )));

        fs.add(new Folder("thispc/C/windows", "Windows", "thispc/C", list(
                folder("System32", "thispc/C/windows/system32"),
                file("win.ini", 392, "2024/1/1 00:00", "配置设置")
        )));

        fs.add(new Folder("thispc/C/windows/system32", "System32", "thispc/C/windows", list(
                file("kernel32.dll", 798_720, "2024/1/1 00:00", "应用程序扩展")
        )));

        fs.add(new Folder("thispc/C/program-files", "Program Files", "thispc/C", list(
                folder("Information Era", "thispc/C/program-files/information-era"),
                folder("Common Files", "thispc/C/program-files/common-files")
        )));

        fs.add(new Folder("thispc/C/program-files/information-era", "Information Era", "thispc/C/program-files", list(
                file("informationera.jar", 12_456_960, "2024/6/12 13:08", "JAR 文件")
        )));

        fs.add(new Folder("thispc/C/program-files/common-files", "Common Files", "thispc/C/program-files", list(
                file("readme.txt", 1_024, "2024/1/1 00:00", "文本文档")
        )));

        fs.add(new Folder("thispc/C/users", "用户", "thispc/C", list(
                folder("Public", "thispc/C/users/public"),
                folder("sighs", "thispc/C/users/sighs")
        )));

        fs.add(new Folder("thispc/C/users/public", "Public", "thispc/C/users", list(
                file("desktop.ini", 220, "2024/1/1 00:00", "配置设置")
        )));

        fs.add(new Folder("thispc/C/users/sighs", "sighs", "thispc/C/users", list(
                folder("Downloads", "thispc/C/users/sighs/downloads"),
                file("desktop.ini", 220, "2024/1/1 00:00", "配置设置")
        )));

        fs.add(new Folder("thispc/C/users/sighs/downloads", "Downloads", "thispc/C/users/sighs", list()));

        fs.add(new Folder("thispc/D", "本地磁盘 (D:)", "thispc", list(
                folder("Games", "thispc/D/games"),
                folder("Work", "thispc/D/work"),
                file("备份.zip", 1_048_576_000L, "2024/6/1 02:00", "压缩文件夹")
        )));

        fs.add(new Folder("thispc/D/games", "Games", "thispc/D", list(
                file("minecraft.exe", 42_150_400, "2024/5/30 18:00", "应用程序")
        )));

        fs.add(new Folder("thispc/D/work", "Work", "thispc/D", list(
                folder("ProjectA", "thispc/D/work/project-a")
        )));

        fs.add(new Folder("thispc/D/work/project-a", "ProjectA", "thispc/D/work", list(
                file("main.java", 18_240, "2024/6/11 23:00", "JAVA 文件")
        )));

        fs.add(new Folder("recyclebin", "回收站", null, list(
                file("旧文件.txt", 512, "2024/5/28 16:00", "文本文档"),
                file("已删除的截图.png", 1_208_320, "2024/5/27 09:00", "PNG 图片")
        )));

        return fs;
    }

    private void add(Folder folder) {
        folders.put(folder.key, folder);
    }

    private static Entry folder(String name, String childKey) {
        return new Entry(name, true, -1L, "2024/6/12 14:30", "文件夹", childKey);
    }

    private static Entry drive(String name, String childKey) {
        return new Entry(name, true, -1L, "2024/6/12 14:30", "本地磁盘", childKey);
    }

    private static Entry file(String name, long size, String modified, String type) {
        return new Entry(name, false, size, modified, type, null);
    }

    @SafeVarargs
    private static List<Entry> list(Entry... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }
}
