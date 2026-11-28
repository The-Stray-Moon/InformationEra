package cc.sighs.informationera.client;

import cc.sighs.informationera.disk.DiskFile;
import cc.sighs.informationera.disk.DiskFolder;
import cc.sighs.informationera.disk.ItemKey;
import cc.sighs.informationera.disk.PlayerDisk;
import cc.sighs.informationera.util.ItemStacks;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资源管理器的客户端视图模型：把服务端的 {@link PlayerDisk} 投影成界面用的条目树。
 *
 * <h2>这里没有任何写操作</h2>
 * 所有变更都必须发请求给服务端（见 {@link DiskClient}），服务端执行后回推新的权威状态，
 * 本类被整体重建。因此客户端不可能出现「本地改了但服务端没改」的不一致，也无法凭空造出物品。
 *
 * <h2>文件 = 物品的别名</h2>
 * 文件条目展示的数量来自 {@link PlayerDisk#quantityOf(ItemKey)}，所以同一种物品的多个文件
 * 天然共享同一个数值——任何一处的数量变化都会同时反映到全部文件上。
 */
public final class VirtualFileSystem {

    /** 一个界面条目：文件夹或物品文件。 */
    public static final class Entry {
        /** 节点稳定 id；文件夹与文件共用同一命名空间。 */
        public final String id;
        public final String name;
        public final boolean folder;
        /** 文件才有：该物品在库存中的数量；文件夹为 -1。 */
        public final long count;
        /** 文件夹才有：直接子节点数；文件为 0。 */
        public final int childCount;
        /** 文件才有：物品标识；文件夹为 null。 */
        public final ItemKey itemKey;
        /** 文件才有：物品 id，例如 {@code minecraft:apple}。 */
        public final String itemId;
        /** 显示用类型文本（本地化）。 */
        public final String type;
        public final String modified;
        public final String created;
        public final String author;
        /** 仅文件夹有意义：进入该文件夹所用的 key（等于 id，保留以兼容既有调用点）。 */
        public final String childKey;
        /** 该条目实际所在的父文件夹 key；扁平/子树视图用它定位真实来源。 */
        public final String parentFolderKey;
        /**
         * 快捷方式目标：该功能已取消（文件本身就是物品引用，Alt+拖动即为复制引用），
         * 恒为 null。保留字段是为了不牵动既有的菜单渲染与判定代码。
         */
        public final String shortcutTargetKey;
        /** 回收站内的条目：原父文件夹 id；回收站外为 null。 */
        public final String recycleOrigin;
        /** 该节点当前被剪切，用于虚化显示。 */
        public final boolean cut;

        /** 兼容旧调用点：文件的「大小」语义现在就是数量。 */
        public final long size;

        private Entry(String id, String name, boolean folder, int childCount, long count, ItemKey itemKey,
                      String itemId, String type, String modified, String created, String author,
                      String childKey, String parentFolderKey, String recycleOrigin, boolean cut) {
            this.id = id;
            this.name = name;
            this.folder = folder;
            this.childCount = childCount;
            this.count = count;
            this.itemKey = itemKey;
            this.itemId = itemId;
            this.type = type;
            this.modified = modified;
            this.created = created;
            this.author = author;
            this.childKey = childKey;
            this.parentFolderKey = parentFolderKey;
            this.recycleOrigin = recycleOrigin;
            this.cut = cut;
            this.size = folder ? -1L : count;
            this.shortcutTargetKey = null;
        }

        public boolean isRecycleEntry() {
            return recycleOrigin != null;
        }
    }

    /** 一个文件夹（供导航树与路径解析使用）。 */
    public static final class Folder {
        public final String key;
        public String name;
        public String parentKey;
        public final List<Entry> entries = new ArrayList<>();

        private Folder(String key, String name, String parentKey) {
            this.key = key;
            this.name = name;
            this.parentKey = parentKey;
        }
    }

    private final Map<String, Folder> folders = new LinkedHashMap<>();
    private final Map<String, String> parents = new LinkedHashMap<>();
    private final Set<String> cutIds = new HashSet<>();
    private String diskName = "";
    private int recycleCount;

    private VirtualFileSystem() {
    }

    /** 空盘：服务端状态尚未到达时的初始视图。 */
    public static VirtualFileSystem create() {
        VirtualFileSystem fs = new VirtualFileSystem();
        String diskLabel = FileExplorerText.t("informationera.fe.disk_unnamed");
        fs.addHost();
        fs.folders.put(DiskFolder.ROOT_ID, new Folder(DiskFolder.ROOT_ID, diskLabel, DiskFolder.HOST_ID));
        for (String id : DiskFolder.PRESET_IDS) {
            String key = DiskFolder.presetTranslationKey(id);
            fs.folders.put(id, new Folder(id, key == null ? id : FileExplorerText.t(key), DiskFolder.ROOT_ID));
        }
        fs.folders.put(DiskFolder.RECYCLE_ID,
                new Folder(DiskFolder.RECYCLE_ID, FileExplorerText.t("informationera.fe.recycle_bin"), DiskFolder.ROOT_ID));
        fs.attachRootToHost(diskLabel, DiskFolder.PRESET_IDS.length + 1);
        return fs;
    }

    /** 建一个「玩家名」虚拟目录：它不在磁盘数据里，只是把磁盘挂在更上一层。 */
    private void addHost() {
        folders.put(DiskFolder.HOST_ID, new Folder(DiskFolder.HOST_ID, hostName(), null));
    }

    /** 把磁盘根挂到玩家名目录下——界面上最上层是玩家名，进去才是磁盘。 */
    private void attachRootToHost(String diskLabel, int childCount) {
        Folder host = folders.get(DiskFolder.HOST_ID);
        if (host == null) {
            return;
        }
        host.entries.add(new Entry(DiskFolder.ROOT_ID, diskLabel, true, childCount, -1L, null, null,
                FileExplorerText.t("informationera.fe.folder_type"), "", "", null,
                DiskFolder.ROOT_ID, DiskFolder.HOST_ID, null, false));
        parents.put(DiskFolder.ROOT_ID, DiskFolder.HOST_ID);
    }

    /** 最上层那个目录的名字就是玩家名。 */
    private static String hostName() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc.player == null ? "" : mc.player.getName().getString();
    }

    /** 从服务端镜像构建视图模型。 */
    public static VirtualFileSystem project(PlayerDisk disk, HolderLookup.Provider registries, Set<String> cut) {
        VirtualFileSystem fs = new VirtualFileSystem();
        if (disk == null) {
            return create();
        }
        fs.diskName = disk.diskName();
        if (cut != null) {
            fs.cutIds.addAll(cut);
        }
        fs.addHost();
        DiskFolder root = disk.root();
        if (root != null) {
            // 磁盘自己也是一层：名字是磁盘名，父级是玩家名目录。
            fs.folders.put(root.id(), new Folder(root.id(), root.name(), DiskFolder.HOST_ID));
        }
        for (DiskFolder folder : disk.allFolders()) {
            if (root != null && folder.id().equals(root.id())) {
                continue;
            }
            // 走 displayFolderName：预置分类文件夹按玩家语言显示。
            fs.folders.put(folder.id(), new Folder(folder.id(), displayFolderName(folder), folder.parentId()));
        }
        for (DiskFolder folder : disk.allFolders()) {
            Folder target = fs.folders.get(folder.id());
            if (target == null) {
                continue;
            }
            for (String childId : disk.childrenOf(folder.id())) {
                fs.parents.put(childId, folder.id());
                DiskFile file = disk.file(childId);
                if (file != null) {
                    target.entries.add(fs.fileEntry(disk, file, registries, folder.id()));
                } else {
                    DiskFolder child = disk.folder(childId);
                    if (child != null) {
                        target.entries.add(fs.folderEntry(disk, child, folder.id()));
                    }
                }
            }
        }
        fs.recycleCount = disk.recycleCount();
        if (root != null) {
            // 最后把磁盘挂到玩家名目录下：界面上的层级就是「玩家名 → 磁盘 → 磁盘内容」。
            fs.attachRootToHost(root.name(), disk.childrenOf(root.id()).size());
        }
        return fs;
    }

    private Entry folderEntry(PlayerDisk disk, DiskFolder folder, String parentFolderKey) {
        String origin = disk.isInRecycleBin(folder.id()) ? parents.get(folder.id()) : null;
        return new Entry(folder.id(), displayFolderName(folder), true, disk.childrenOf(folder.id()).size(), -1L, null, null,
                FileExplorerText.t("informationera.fe.folder_type"),
                formatTime(folder.updatedAt()), formatTime(folder.createdAt()), null,
                folder.id(), parentFolderKey, origin, cutIds.contains(folder.id()));
    }

    /**
     * 文件夹的显示名。
     *
     * <p>预置分类文件夹在磁盘里存的是英文默认名，显示时换成玩家语言；
     * 一旦玩家改过名（名字不再等于默认值），就按玩家的命名显示，不再覆盖。
     */
    private static String displayFolderName(DiskFolder folder) {
        String key = DiskFolder.presetTranslationKey(folder.id());
        if (key == null) {
            // 根目录用磁盘名（也就是玩家名字）呈现。
            return folder.name();
        }
        String defaultName = DiskFolder.presetDefaultName(folder.id());
        if (defaultName != null && defaultName.equals(folder.name())) {
            return FileExplorerText.t(key);
        }
        return folder.name();
    }

    private Entry fileEntry(PlayerDisk disk, DiskFile file, HolderLookup.Provider registries, String parentFolderKey) {
        long count = disk.quantityOf(file.key());
        String origin = disk.isInRecycleBin(file.id()) ? parents.get(file.id()) : null;
        // 名字直接来自磁盘：入库时就写的是物品的显示名，所以这里原样显示。
        return new Entry(file.id(), file.name(), false, 0, count, file.key(),
                file.key().itemId(),
                FileExplorerText.t("informationera.fe.item_type"),
                formatTime(file.updatedAt()), formatTime(file.createdAt()), null,
                null, parentFolderKey, origin, cutIds.contains(file.id()));
    }

    /** 展示用物品栈：数量已夹到堆叠上限，真实数量由 {@link #overlayTextOf} 呈现。 */
    public ItemStack iconStackOf(Entry entry, HolderLookup.Provider registries) {
        if (entry == null || entry.folder || entry.itemKey == null) {
            return ItemStack.EMPTY;
        }
        return ItemStacks.stackOf(entry.itemKey, entry.count, registries);
    }

    /** 数量角标文本；少于 2 个返回 null（不需要角标）。 */
    public static String overlayTextOf(Entry entry) {
        return entry == null || entry.folder ? null : ItemStacks.overlayText(entry.count);
    }

    public void setCut(String nodeId, boolean cut) {
        if (nodeId == null) {
            return;
        }
        if (cut) {
            cutIds.add(nodeId);
        } else {
            cutIds.remove(nodeId);
        }
    }

    public void clearCut() {
        cutIds.clear();
    }

    public String diskName() {
        return diskName;
    }

    public int recycleCount() {
        return recycleCount;
    }

    /** 显示名重命名（仅改内存视图；真正持久化由服务端状态推回时覆盖）。 */
    public void setDiskName(String name) {
        if (name != null && !name.isEmpty()) {
            Folder root = folders.get(DiskFolder.ROOT_ID);
            if (root != null) {
                root.name = name;
            }
            this.diskName = name;
        }
    }

    public Folder folder(String key) {
        return key == null ? null : folders.get(key);
    }

    public boolean hasChildFolders(String key) {
        Folder folder = folders.get(key);
        if (folder == null) {
            return false;
        }
        for (Entry entry : folder.entries) {
            if (entry.folder) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code key} 是否就是 {@code ancestor} 或位于它的子树里。
     *
     * <p>文件夹 id 不是路径，不能靠字符串前缀判断；这里沿 parentKey 向上走，
     * 并用 visited 防住异常数据造成的循环。
     */
    public boolean isWithin(String key, String ancestor) {
        if (key == null || ancestor == null) {
            return false;
        }
        if (key.equals(ancestor)) {
            return true;
        }
        Set<String> visited = new HashSet<>();
        String current = key;
        while (current != null && visited.add(current)) {
            Folder folder = folders.get(current);
            if (folder == null || folder.parentKey == null) {
                return false;
            }
            if (folder.parentKey.equals(ancestor)) {
                return true;
            }
            current = folder.parentKey;
        }
        return false;
    }

    public List<Entry> childFolders(String key) {
        List<Entry> result = new ArrayList<>();
        Folder folder = folders.get(key);
        if (folder == null) {
            return result;
        }
        for (Entry entry : folder.entries) {
            if (entry.folder) {
                result.add(entry);
            }
        }
        return result;
    }

    /** 用显示名或 id 解析出文件夹 key，找不到返回 null。 */
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

    /** 兼容旧调用点。 */
    public boolean hasName(String folderKey, String name) {
        return contains(folderKey, name);
    }

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

    /** 自然排序：数字段按数值比较，其余忽略大小写。 */
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

    /** 时间戳的友好显示。 */
    static String formatTime(long millis) {
        if (millis <= 0L) {
            return "";
        }
        long delta = System.currentTimeMillis() - millis;
        if (delta < 60_000L) {
            return FileExplorerText.t("informationera.fe.just_now");
        }
        if (delta < 86_400_000L) {
            return String.format(Locale.ROOT, "%1$tY-%1$tm-%1$td %1$tH:%1$tM", millis);
        }
        return String.format(Locale.ROOT, "%1$tY-%1$tm-%1$td %1$tH:%1$tM", millis);
    }

    public List<Folder> allFolders() {
        return new ArrayList<>(folders.values());
    }
}
