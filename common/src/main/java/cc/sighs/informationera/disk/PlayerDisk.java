package cc.sighs.informationera.disk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一个玩家的「信息磁盘」：无限容量的物品库存 + 用来整理它的文件/文件夹树。
 *
 * <h2>核心模型：文件是物品的别名，不是物品的容器</h2>
 *
 * <ul>
 *   <li>库存（{@link #quantityOf(ItemKey)}）是唯一真实的物品数量，每种物品只有一份。</li>
 *   <li>文件（{@link DiskFile}）只是某种物品的一个命名引用。同一种物品可以在任意多个
 *       文件夹里各有一个文件，但它们显示的是同一份数量。</li>
 *   <li>因此「复制文件」不产生物品，只是多一个引用；任何一处数量变化（取出、并入）
 *       都会立刻反映到全部同物品的文件上。</li>
 *   <li>库存按「是否还有文件引用」存活：当某种物品的最后一个文件被彻底删除时，
 *       该物品就从库存中消失。所以彻底删除文件 = 摧毁这部分物品。</li>
 * </ul>
 *
 * <p>本类不含任何平台类型，可在 common 层与任意加载器/版本共用。
 * 所有变更方法均返回 {@link OpResult}；失败时磁盘状态保持不变。
 */
public final class PlayerDisk {

    /** 入库/取出单个操作的语义。 */
    public enum TransferMode {
        /** 只处理一个物品槽的当前内容。 */
        SINGLE,
        /** 尽量多地处理（例如「收进全部」）。 */
        ALL
    }

    private final String diskName;
    private final Map<String, DiskFolder> folders = new LinkedHashMap<>();
    /** 每个文件夹下按显示顺序排列的子节点 id（文件夹 id 与文件 id 混排）。 */
    private final Map<String, List<String>> children = new LinkedHashMap<>();
    /** 物品库存：key 有序，便于界面稳定排序。 */
    private final TreeMap<ItemKey, Long> inventory = new TreeMap<>();
    /** 回收站记录：节点 id -> 原父文件夹 id。 */
    private final Map<String, String> recycleOrigin = new LinkedHashMap<>();
    /** 回收站记录：节点 id -> 删除时间。 */
    private final Map<String, Long> recycleDeletedAt = new LinkedHashMap<>();

    private long idCounter;
    /** 回收站保留天数；{@code <= 0} 表示不自动清理。 */
    private int recycleRetentionDays = 30;

    /**
     * 创建一个玩家的磁盘。
     *
     * <p>根节点的显示名就是玩家名字，根下预置四个默认分类文件夹（方块 / 食物 / 工具 / 武器），
     * 最后是回收站。没有资源管理器窗口打开时，存进来的物品就落在根目录。
     */
    public PlayerDisk(String diskName) {
        this.diskName = diskName == null || diskName.isEmpty() ? "Information Disk" : diskName;
        long now = System.currentTimeMillis();
        DiskFolder root = new DiskFolder(DiskFolder.ROOT_ID, this.diskName, null, now, now);
        folders.put(root.id(), root);
        children.put(root.id(), new ArrayList<>());

        // 默认分类文件夹。名称交给 target 层在做本地化准备时替换成玩家语言。
        addPresetFolder(root.id(), DiskFolder.BLOCKS_ID, "Blocks", now);
        addPresetFolder(root.id(), DiskFolder.FOOD_ID, "Food", now);
        addPresetFolder(root.id(), DiskFolder.TOOLS_ID, "Tools", now);
        addPresetFolder(root.id(), DiskFolder.WEAPONS_ID, "Weapons", now);

        // 回收站固定在根目录末尾，不参与普通排序。
        DiskFolder recycle = new DiskFolder(DiskFolder.RECYCLE_ID, "Recycle Bin", root.id(), now, now);
        folders.put(recycle.id(), recycle);
        children.put(recycle.id(), new ArrayList<>());
        children.get(root.id()).add(recycle.id());
    }

    private void addPresetFolder(String parentId, String id, String name, long now) {
        DiskFolder folder = new DiskFolder(id, name, parentId, now, now);
        folders.put(folder.id(), folder);
        children.put(folder.id(), new ArrayList<>());
        children.get(parentId).add(folder.id());
    }

    /**
     * 没有窗口打开时的入库落点：根目录。
     *
     * <p>这是需求里的明确规则——玩家没打开资源管理器时，Alt+左键存进来的物品直接进根目录，
     * 而不是被塞进某个默认分类里。
     */
    public String defaultDropFolderId() {
        return DiskFolder.ROOT_ID;
    }

    // ---------------------------------------------------------------- 基本信息

    public String diskName() {
        return diskName;
    }

    /** 磁盘根文件夹。 */
    public DiskFolder root() {
        return folders.get(DiskFolder.ROOT_ID);
    }

    public DiskFolder folder(String id) {
        return id == null ? null : folders.get(id);
    }

    public DiskFile file(String id) {
        return id == null ? null : files().get(id);
    }

    /** 节点可能是文件夹也可能是文件，统一取显示名。 */
    public String nameOf(String id) {
        DiskFolder folder = folders.get(id);
        if (folder != null) {
            return folder.name();
        }
        DiskFile file = file(id);
        return file == null ? null : file.name();
    }

    public boolean isFolder(String id) {
        return folders.containsKey(id);
    }

    public boolean exists(String id) {
        return folders.containsKey(id) || file(id) != null;
    }

    public boolean isInRecycleBin(String id) {
        return recycleOrigin.containsKey(id);
    }

    /** 文件夹的直接子节点 id（不含回收站内部规则之外的任何过滤）。 */
    public List<String> childrenOf(String folderId) {
        List<String> list = children.get(folderId);
        return list == null ? Collections.<String>emptyList() : Collections.unmodifiableList(list);
    }

    public List<DiskFolder> allFolders() {
        return new ArrayList<>(folders.values());
    }

    public Map<String, DiskFile> files() {
        return fileMap;
    }

    /**
     * 回收站里的条目数。
     *
     * <p>只数直接子节点，不递归：这对应界面里用户看到的行数。
     * 递归统计会把「一个文件夹 + 里面的文件」算成两项，与视觉不符。
     */
    public int recycleCount() {
        List<String> top = children.get(DiskFolder.RECYCLE_ID);
        return top == null ? 0 : top.size();
    }

    public int recycleRetentionDays() {
        return recycleRetentionDays;
    }

    /**
     * 遍历回收站登记信息，供编解码复用。
     *
     * @param consumer 接收 (节点 id, 原父文件夹 id, 删除时间)
     */
    public void recycleRecords(RecycleRecordConsumer consumer) {
        if (consumer == null) {
            return;
        }
        for (Map.Entry<String, String> entry : recycleOrigin.entrySet()) {
            Long deletedAt = recycleDeletedAt.get(entry.getKey());
            consumer.accept(entry.getKey(), entry.getValue(), deletedAt == null ? 0L : deletedAt);
        }
    }

    /** 回收站登记信息的遍历回调。 */
    public interface RecycleRecordConsumer {
        void accept(String nodeId, String originParentId, long deletedAt);
    }

    public void setRecycleRetentionDays(int days) {
        this.recycleRetentionDays = days;
    }

    // ---------------------------------------------------------------- 库存查询

    /** 某种物品的数量；不存在返回 0。 */
    public long quantityOf(ItemKey key) {
        Long value = inventory.get(key);
        return value == null ? 0L : value;
    }

    public Map<ItemKey, Long> inventory() {
        return Collections.unmodifiableMap(inventory);
    }

    /** 所有文件引用到的物品种类数。 */
    public int stockKinds() {
        return inventory.size();
    }

    // ---------------------------------------------------------------- 文件夹操作

    /** 在 parentId 下新建文件夹。 */
    public OpResult createFolder(String parentId, String name) {
        DiskFolder parent = folders.get(parentId);
        if (parent == null) {
            return OpResult.fail("informationera.msg.folder_missing");
        }
        if (depthOf(parentId) >= DiskFolder.MAX_DEPTH) {
            return OpResult.fail("informationera.msg.too_deep");
        }
        String clean = sanitizeName(name);
        if (clean.isEmpty()) {
            clean = "New folder";
        }
        if (hasChildNamed(parentId, clean)) {
            return OpResult.fail("informationera.msg.name_taken", clean);
        }
        long now = System.currentTimeMillis();
        DiskFolder created = new DiskFolder(nextId("f"), clean, parentId, now, now);
        folders.put(created.id(), created);
        children.put(created.id(), new ArrayList<>());
        children.get(parentId).add(created.id());
        parent.touch(now);
        return OpResult.ok("informationera.msg.folder_created", clean);
    }

    /** 重命名文件或文件夹。回收站本身不可重命名。 */
    public OpResult rename(String nodeId, String newName) {
        if (DiskFolder.RECYCLE_ID.equals(nodeId)) {
            return OpResult.fail("informationera.msg.protected_node");
        }
        String parentId = parentOf(nodeId);
        if (parentId == null) {
            return OpResult.fail("informationera.msg.node_missing");
        }
        String clean = sanitizeName(newName);
        if (clean.isEmpty()) {
            return OpResult.fail("informationera.msg.name_empty");
        }
        if (hasChildNamedOtherThan(parentId, clean, nodeId)) {
            return OpResult.fail("informationera.msg.name_taken", clean);
        }
        long now = System.currentTimeMillis();
        DiskFolder folder = folders.get(nodeId);
        if (folder != null) {
            folder.rename(clean);
            folder.touch(now);
        } else {
            DiskFile file = file(nodeId);
            if (file == null) {
                return OpResult.fail("informationera.msg.node_missing");
            }
            file.rename(clean);
            file.touch(now);
        }
        return OpResult.ok("informationera.msg.renamed", clean);
    }

    /**
     * 移动或复制节点到 targetFolderId。
     *
     * <p>复制文件不会产生新物品——新文件与源文件引用同一份库存。复制文件夹会递归复制
     * 其下全部子树，文件同样只增加引用。
     */
    public OpResult transfer(List<String> nodeIds, String targetFolderId, boolean copy) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return OpResult.fail("informationera.msg.nothing_selected");
        }
        DiskFolder target = folders.get(targetFolderId);
        if (target == null) {
            return OpResult.fail("informationera.msg.folder_missing");
        }
        if (target.isRecycleBin()) {
            return OpResult.fail("informationera.msg.not_a_destination");
        }
        // 先做全部合法性检查，避免出现"搬了一半"的状态。
        for (String id : nodeIds) {
            if (!exists(id)) {
                return OpResult.fail("informationera.msg.node_missing");
            }
            if (DiskFolder.RECYCLE_ID.equals(id) || DiskFolder.ROOT_ID.equals(id) || isAncestorOf(id, targetFolderId)) {
                return OpResult.fail("informationera.msg.invalid_move");
            }
            if (isInRecycleBin(id)) {
                return OpResult.fail("informationera.msg.restore_first");
            }
        }
        if (!copy && depthOf(targetFolderId) + 1 + subtreeHeight(nodeIds) > DiskFolder.MAX_DEPTH) {
            return OpResult.fail("informationera.msg.too_deep");
        }

        long now = System.currentTimeMillis();
        int moved = 0;
        for (String id : nodeIds) {
            String parentId = parentOf(id);
            if (parentId == null) {
                continue;
            }
            if (copy) {
                copyNode(id, targetFolderId);
            } else {
                children.get(parentId).remove(id);
                children.get(targetFolderId).add(id);
                DiskFolder folder = folders.get(id);
                if (folder != null) {
                    folder.setParentId(targetFolderId);
                    folder.touch(now);
                }
            }
            moved++;
        }
        target.touch(now);
        return OpResult.ok(copy ? "informationera.msg.copied" : "informationera.msg.moved", String.valueOf(moved));
    }

    // ---------------------------------------------------------------- 回收站

    /** 把节点移入回收站（可恢复）。 */
    public OpResult toRecycleBin(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return OpResult.fail("informationera.msg.nothing_selected");
        }
        long now = System.currentTimeMillis();
        int count = 0;
        for (String id : nodeIds) {
            if (DiskFolder.RECYCLE_ID.equals(id) || DiskFolder.ROOT_ID.equals(id) || isInRecycleBin(id)) {
                continue;
            }
            String parentId = parentOf(id);
            if (parentId == null) {
                continue;
            }
            children.get(parentId).remove(id);
            // 回收站内可能已有同名条目，重命名以免冲突。
            if (hasChildNamed(DiskFolder.RECYCLE_ID, nameOf(id))) {
                String renamed = uniqueName(DiskFolder.RECYCLE_ID, nameOf(id));
                DiskFolder folder = folders.get(id);
                if (folder != null) {
                    folder.rename(renamed);
                } else if (file(id) != null) {
                    file(id).rename(renamed);
                }
            }
            children.get(DiskFolder.RECYCLE_ID).add(id);
            DiskFolder folder = folders.get(id);
            if (folder != null) {
                folder.setParentId(DiskFolder.RECYCLE_ID);
            }
            recycleOrigin.put(id, parentId);
            recycleDeletedAt.put(id, now);
            count++;
        }
        if (count == 0) {
            return OpResult.fail("informationera.msg.nothing_selected");
        }
        return OpResult.ok("informationera.msg.recycled", String.valueOf(count));
    }

    /** 从回收站还原节点到原位置；原位置已不存在时回到根目录。 */
    public OpResult restoreFromRecycleBin(List<String> nodeIds) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (String id : nodeIds) {
            if (!recycleOrigin.containsKey(id)) {
                continue;
            }
            String origin = recycleOrigin.get(id);
            if (folders.get(origin) == null || DiskFolder.RECYCLE_ID.equals(origin)) {
                origin = DiskFolder.ROOT_ID;
            }
            children.get(DiskFolder.RECYCLE_ID).remove(id);
            if (hasChildNamed(origin, nameOf(id))) {
                String renamed = uniqueName(origin, nameOf(id));
                DiskFolder folder = folders.get(id);
                if (folder != null) {
                    folder.rename(renamed);
                } else if (file(id) != null) {
                    file(id).rename(renamed);
                }
            }
            children.get(origin).add(id);
            DiskFolder folder = folders.get(id);
            if (folder != null) {
                folder.setParentId(origin);
                folder.touch(now);
            }
            recycleOrigin.remove(id);
            recycleDeletedAt.remove(id);
            count++;
        }
        if (count == 0) {
            return OpResult.fail("informationera.msg.nothing_to_restore");
        }
        return OpResult.ok("informationera.msg.restored", String.valueOf(count));
    }

    /**
     * 彻底删除节点：连同子树一起从磁盘移除，并丢弃它们所独占的物品数量。
     * 被其他文件引用的物品数量不受影响。
     */
    public OpResult purge(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return OpResult.fail("informationera.msg.nothing_selected");
        }
        int count = 0;
        for (String id : nodeIds) {
            if (!exists(id)) {
                continue;
            }
            String parentId = parentOf(id);
            if (parentId != null) {
                children.get(parentId).remove(id);
            }
            removeSubtree(id);
            count++;
        }
        if (count == 0) {
            return OpResult.fail("informationera.msg.nothing_selected");
        }
        return OpResult.ok("informationera.msg.deleted", String.valueOf(count));
    }

    /** 清空回收站：彻底删除其全部内容。 */
    public OpResult emptyRecycleBin() {
        List<String> top = new ArrayList<>(children.getOrDefault(DiskFolder.RECYCLE_ID, Collections.<String>emptyList()));
        if (top.isEmpty()) {
            return OpResult.fail("informationera.msg.recycle_empty");
        }
        for (String id : top) {
            children.get(DiskFolder.RECYCLE_ID).remove(id);
            removeSubtree(id);
        }
        return OpResult.ok("informationera.msg.recycle_emptied", String.valueOf(top.size()));
    }

    /** 按保留天数清理回收站，返回被清理的条目数。 */
    public int purgeExpiredRecycleEntries(long now) {
        if (recycleRetentionDays <= 0) {
            return 0;
        }
        long deadline = now - recycleRetentionDays * 24L * 60L * 60L * 1000L;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : recycleDeletedAt.entrySet()) {
            if (entry.getValue() < deadline) {
                expired.add(entry.getKey());
            }
        }
        int removed = 0;
        for (String id : expired) {
            if (!exists(id)) {
                recycleOrigin.remove(id);
                recycleDeletedAt.remove(id);
                continue;
            }
            String parentId = parentOf(id);
            if (parentId != null) {
                children.get(parentId).remove(id);
            }
            removeSubtree(id);
            removed++;
        }
        return removed;
    }

    // ---------------------------------------------------------------- 库存变更

    /**
     * 入库：把某种物品的数量并入库存。
     *
     * @param folderId     希望引用该物品的文件夹；为 null 时沿用已有文件所在文件夹，
     *                     都没有时放在根目录
     * @param key          物品标识
     * @param amount       数量，必须为正
     */
    /**
     * 入库：把某种物品的数量并入库存，必要时新建一个文件来引用它。
     *
     * @param folderId 希望引用该物品的文件夹；为 null 时沿用已有文件所在文件夹，都没有时放根目录
     * @param key      物品标识
     * @param amount   数量，必须为正
     * @param name     新建文件时使用的显示名；为空则用 id 派生的名字
     */
    public OpResult deposit(String folderId, ItemKey key, long amount, String name) {
        if (key == null || amount <= 0L) {
            return OpResult.fail("informationera.msg.invalid_amount");
        }
        String existing = firstFileIdOf(key);
        inventory.merge(key, amount, Long::sum);
        String target = folderId != null && folders.containsKey(folderId) ? folderId
                : (existing != null ? parentOf(existing) : DiskFolder.ROOT_ID);
        if (target == null || !folders.containsKey(target)) {
            target = DiskFolder.ROOT_ID;
        }
        // 库存必须始终有文件可达，否则会出现"有数量但看不到文件"的悬空物品。
        // 已经有文件引用这种物品时不再新建，也就不会改动它的名字——名字属于玩家，
        // 后续入库不应该把它重置掉。
        if (existing == null) {
            long now = System.currentTimeMillis();
            String displayName = name == null || name.isBlank() ? DiskFile.defaultName(key) : name.trim();
            DiskFile file = new DiskFile(nextId("d"), key, displayName, now, now);
            fileMap.put(file.id(), file);
            children.get(target).add(file.id());
        }
        return OpResult.ok("informationera.msg.deposited", String.valueOf(amount));
    }

    /** 入库（不指定显示名，用 id 派生的名字）。 */
    public OpResult deposit(String folderId, ItemKey key, long amount) {
        return deposit(folderId, key, amount, null);
    }

    /**
     * 从库存取出最多 amount 个物品，返回实际取出的数量。
     *
     * <p>取出后库存减少，全部同物品文件的显示数量同步减少。**当库存被取空时，
     * 引用该物品的所有文件会被一并移除**——文件只是库存的别名，库存没了别名就没有意义，
     * 留一个数量为 0 的文件在界面上纯属垃圾。
     */
    public long withdraw(ItemKey key, long amount) {
        if (key == null || amount <= 0L) {
            return 0L;
        }
        long available = quantityOf(key);
        long taken = Math.min(available, amount);
        if (taken <= 0L) {
            return 0L;
        }
        long left = available - taken;
        if (left <= 0L) {
            inventory.remove(key);
            removeFilesOfKind(key);
        } else {
            inventory.put(key, left);
        }
        return taken;
    }

    /** 移除所有引用该物品的文件（含回收站里的），用于库存取空后的清理。 */
    private void removeFilesOfKind(ItemKey key) {
        List<String> doomed = new ArrayList<>();
        for (DiskFile file : fileMap.values()) {
            if (file.key().equals(key)) {
                doomed.add(file.id());
            }
        }
        for (String id : doomed) {
            String parentId = parentOf(id);
            if (parentId != null) {
                List<String> siblings = children.get(parentId);
                if (siblings != null) {
                    siblings.remove(id);
                }
            }
            fileMap.remove(id);
            recycleOrigin.remove(id);
            recycleDeletedAt.remove(id);
        }
    }

    // ---------------------------------------------------------------- 快照（撤销/持久化）

    /** 非破坏性快照，用于撤销与重做。 */
    public static final class Snapshot {
        private final Map<String, DiskFolder> folders;
        private final Map<String, List<String>> children;
        private final TreeMap<ItemKey, Long> inventory;
        private final Map<String, DiskFile> files;
        private final Map<String, String> recycleOrigin;
        private final Map<String, Long> recycleDeletedAt;
        private final long idCounter;

        private Snapshot(PlayerDisk disk) {
            this.folders = new LinkedHashMap<>();
            for (Map.Entry<String, DiskFolder> entry : disk.folders.entrySet()) {
                DiskFolder folder = entry.getValue();
                this.folders.put(entry.getKey(), new DiskFolder(folder.id(), folder.name(),
                        folder.parentId(), folder.createdAt(), folder.updatedAt()));
            }
            this.children = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : disk.children.entrySet()) {
                this.children.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            this.inventory = new TreeMap<>(disk.inventory);
            this.files = new LinkedHashMap<>();
            for (Map.Entry<String, DiskFile> entry : disk.fileMap.entrySet()) {
                this.files.put(entry.getKey(), entry.getValue().copy());
            }
            this.recycleOrigin = new LinkedHashMap<>(disk.recycleOrigin);
            this.recycleDeletedAt = new LinkedHashMap<>(disk.recycleDeletedAt);
            this.idCounter = disk.idCounter;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public void restore(Snapshot snapshot) {
        folders.clear();
        folders.putAll(snapshot.folders);
        children.clear();
        children.putAll(snapshot.children);
        inventory.clear();
        inventory.putAll(snapshot.inventory);
        fileMap.clear();
        fileMap.putAll(snapshot.files);
        recycleOrigin.clear();
        recycleOrigin.putAll(snapshot.recycleOrigin);
        recycleDeletedAt.clear();
        recycleDeletedAt.putAll(snapshot.recycleDeletedAt);
        idCounter = snapshot.idCounter;
    }

    // ---------------------------------------------------------------- 内部实现

    private final Map<String, DiskFile> fileMap = new LinkedHashMap<>();

    // ---------------------------------------------------------------- 编解码专用入口
    // DiskCodec 与 PlayerDisk 同包，通过这些方法直接灌入结构，跳过所有业务校验。

    void putFolderRaw(DiskFolder folder) {
        folders.put(folder.id(), folder);
        if (!children.containsKey(folder.id())) {
            children.put(folder.id(), new ArrayList<>());
        }
    }

    void putChildRaw(String parentId, String childId) {
        List<String> list = children.get(parentId);
        if (list == null) {
            list = new ArrayList<>();
            children.put(parentId, list);
        }
        if (!list.contains(childId)) {
            list.add(childId);
        }
    }

    void putFileRaw(DiskFile file) {
        fileMap.put(file.id(), file);
    }

    void putInventoryRaw(ItemKey key, long count) {
        if (key != null && count > 0L) {
            inventory.put(key, count);
        }
    }

    void putRecycleRaw(String nodeId, String originParentId, long deletedAt) {
        recycleOrigin.put(nodeId, originParentId);
        recycleDeletedAt.put(nodeId, deletedAt);
    }

    void setRecycleRetentionDaysRaw(int days) {
        this.recycleRetentionDays = days;
    }

    long idCounterRaw() {
        return idCounter;
    }

    void setIdCounterRaw(long value) {
        this.idCounter = Math.max(this.idCounter, value);
    }

    private String nextId(String prefix) {
        return prefix + "_" + Long.toString(++idCounter, 36);
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        String clean = name.trim();
        StringBuilder sb = new StringBuilder(clean.length());
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>'
                    || c == '|' || c < ' ') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String parentOf(String nodeId) {
        for (Map.Entry<String, List<String>> entry : children.entrySet()) {
            if (entry.getValue().contains(nodeId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private int depthOf(String folderId) {
        int depth = 0;
        String current = folderId;
        while (current != null && !current.isEmpty()) {
            DiskFolder folder = folders.get(current);
            if (folder == null) {
                break;
            }
            current = folder.parentId();
            depth++;
            if (depth > DiskFolder.MAX_DEPTH + 1) {
                break;
            }
        }
        return depth;
    }

    private int subtreeHeight(List<String> nodeIds) {
        int max = 0;
        for (String id : nodeIds) {
            max = Math.max(max, heightOf(id));
        }
        return max;
    }

    private int heightOf(String nodeId) {
        if (!folders.containsKey(nodeId)) {
            return 0;
        }
        int max = 0;
        for (String child : children.getOrDefault(nodeId, Collections.<String>emptyList())) {
            max = Math.max(max, heightOf(child) + 1);
        }
        return max;
    }

    private boolean isAncestorOf(String candidateAncestor, String nodeId) {
        if (candidateAncestor == null || nodeId == null || candidateAncestor.equals(nodeId)) {
            return false;
        }
        String current = nodeId;
        int guard = 0;
        while (current != null && guard++ <= DiskFolder.MAX_DEPTH + 2) {
            DiskFolder folder = folders.get(current);
            if (folder == null) {
                return false;
            }
            if (candidateAncestor.equals(folder.parentId())) {
                return true;
            }
            current = folder.parentId();
        }
        return false;
    }

    private boolean hasChildNamed(String folderId, String name) {
        return hasChildNamedOtherThan(folderId, name, null);
    }

    private boolean hasChildNamedOtherThan(String folderId, String name, String exceptId) {
        List<String> list = children.get(folderId);
        if (list == null) {
            return false;
        }
        for (String id : list) {
            if (exceptId != null && exceptId.equals(id)) {
                continue;
            }
            String childName = nameOf(id);
            if (childName != null && childName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 目标里没有同名就用原名，否则从 (2) 起找第一个空位。回收站重命名用它。 */
    private String uniqueName(String folderId, String base) {
        if (!hasChildNamed(folderId, base)) {
            return base;
        }
        int n = 2;
        while (hasChildNamed(folderId, base + " (" + n + ")")) {
            n++;
        }
        return base + " (" + n + ")";
    }

    /**
     * 生成副本名字，语义对齐 Windows 文件资源管理器。
     *
     * <p>规则统一为一条：**剥掉名字里已存在的 {@code (N)} 后缀得到基础名，再从这个基础名
     * 从 2 开始找第一个没被占用的编号**。
     *
     * <ul>
     *   <li>{@code apple} → {@code apple (2)}（已存在则 (3)、以此类推）</li>
     *   <li>{@code apple (2)} → {@code apple (3)}，不会摞成 {@code apple (2) (2)}</li>
     * </ul>
     */
    private String uniqueCopyName(String folderId, String base) {
        String stem = stripCopySuffix(base);
        if (stem.isEmpty()) {
            stem = base;
        }
        int n = 2;
        while (hasChildNamed(folderId, stem + " (" + n + ")")) {
            n++;
        }
        return stem + " (" + n + ")";
    }

    /** 剥掉结尾的 {@code (数字)} 后缀；没有则原样返回。 */
    private static String stripCopySuffix(String name) {
        if (name == null || !name.endsWith(")")) {
            return name;
        }
        int open = name.lastIndexOf(" (");
        if (open <= 0) {
            return name;
        }
        String inner = name.substring(open + 2, name.length() - 1);
        if (inner.isEmpty()) {
            return name;
        }
        for (int i = 0; i < inner.length(); i++) {
            if (!Character.isDigit(inner.charAt(i))) {
                return name;
            }
        }
        return name.substring(0, open);
    }

    private String firstFileIdOf(ItemKey key) {
        for (DiskFile file : fileMap.values()) {
            if (file.key().equals(key)) {
                return file.id();
            }
        }
        return null;
    }

    /** 复制节点到目标文件夹；文件只增加引用，不产生新物品。 */
    private void copyNode(String sourceId, String targetFolderId) {
        long now = System.currentTimeMillis();
        DiskFolder sourceFolder = folders.get(sourceId);
        if (sourceFolder == null) {
            DiskFile source = file(sourceId);
            if (source == null) {
                return;
            }
            String name = uniqueCopyName(targetFolderId, source.name());
            DiskFile copy = new DiskFile(nextId("d"), source.key(), name, now, now);
            fileMap.put(copy.id(), copy);
            children.get(targetFolderId).add(copy.id());
            return;
        }
        String name = uniqueCopyName(targetFolderId, sourceFolder.name());
        DiskFolder copy = new DiskFolder(nextId("f"), name, targetFolderId, now, now);
        folders.put(copy.id(), copy);
        children.put(copy.id(), new ArrayList<>());
        children.get(targetFolderId).add(copy.id());
        for (String child : new ArrayList<>(children.getOrDefault(sourceId, Collections.<String>emptyList()))) {
            copyNode(child, copy.id());
        }
    }

    /** 彻底移除节点及其子树，并丢弃只被这些文件引用的物品数量。 */
    private void removeSubtree(String nodeId) {
        DiskFolder folder = folders.get(nodeId);
        if (folder != null) {
            for (String child : new ArrayList<>(children.getOrDefault(nodeId, Collections.<String>emptyList()))) {
                removeSubtree(child);
            }
            folders.remove(nodeId);
            children.remove(nodeId);
        } else {
            DiskFile file = fileMap.remove(nodeId);
            if (file != null) {
                dropStockIfUnreferenced(file.key());
            }
        }
        recycleOrigin.remove(nodeId);
        recycleDeletedAt.remove(nodeId);
    }

    /** 若某种物品已没有任何文件引用，则连同库存一起丢弃。 */
    private void dropStockIfUnreferenced(ItemKey key) {
        if (firstFileIdOf(key) == null) {
            inventory.remove(key);
        }
    }
}
