package cc.sighs.informationera.client;

import cc.sighs.informationera.disk.DiskFolder;
import cc.sighs.informationera.disk.ItemKey;
import cc.sighs.informationera.disk.PlayerDisk;
import cc.sighs.informationera.net.DiskRequestPacket;
import cc.sighs.informationera.net.DiskStateHandler;
import cc.sighs.informationera.net.RequestType;
import com.sighs.apricityui.ui.ToastManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 客户端与服务端之间的唯一通道。
 *
 * <p>界面任何「写」动作都走这里发请求，然后等服务端回推权威状态。本类同时负责在状态
 * 到达时重建所有已打开窗口的视图模型，因此界面不需要关心同步细节。
 *
 * <p>这里刻意不缓存任何可变的业务状态：唯一的状态是服务端镜像
 * （{@link DiskStateHandler#mirror()}），视图由它每次重新投影。
 */
public final class DiskClient {

    /** 最近一次被操作聚焦的文件夹，作为「资源管理器当前显示文件夹」的记录。 */
    private static volatile String focusedFolderId = DiskFolder.ROOT_ID;
    /** 是否至少打开过一个窗口。用于判断「当前没有打开资源管理器」。 */
    private static volatile boolean anyWindowOpen;

    private DiskClient() {
    }

    // ---------------------------------------------------------------- 请求发送

    public static void open() {
        send(new DiskRequestPacket(RequestType.OPEN));
    }

    public static void createFolder(String parentId, String name) {
        send(new DiskRequestPacket(RequestType.CREATE_FOLDER, nz(parentId, DiskFolder.ROOT_ID), nz(name, "")));
    }

    public static void rename(String nodeId, String newName) {
        send(new DiskRequestPacket(RequestType.RENAME, nz(nodeId, ""), nz(newName, "")));
    }

    public static void move(List<String> nodeIds, String targetFolderId) {
        send(withTarget(RequestType.MOVE, targetFolderId, nodeIds));
    }

    public static void copy(List<String> nodeIds, String targetFolderId) {
        send(withTarget(RequestType.COPY, targetFolderId, nodeIds));
    }

    public static void toRecycleBin(List<String> nodeIds) {
        send(new DiskRequestPacket(RequestType.TO_RECYCLE, nodeIds));
    }

    public static void restore(String nodeId) {
        send(new DiskRequestPacket(RequestType.RESTORE, List.of(nz(nodeId, ""))));
    }

    public static void restoreAll() {
        send(new DiskRequestPacket(RequestType.RESTORE, allRecycleIds()));
    }

    public static void purge(List<String> nodeIds) {
        send(new DiskRequestPacket(RequestType.PURGE, nodeIds));
    }

    public static void emptyRecycleBin() {
        send(new DiskRequestPacket(RequestType.EMPTY_RECYCLE, allRecycleIds()));
    }

    /**
     * 把指定槽位的物品存入磁盘。
     *
     * @param folderId 目标文件夹；null 表示用聚焦文件夹，再回退到下载目录
     * @param slots    槽位索引列表（玩家当前菜单的 slot index）
     */
    /**
     * 把指定槽位的物品存入磁盘。
     *
     * <p>参数按「槽位索引, 物品显示名」成对传递。名称由客户端提供，因为物品的本地化名
     * 只有客户端知道（服务端拿不到玩家的语言）；服务端把它当作新文件的显示名。
     *
     * @param folderId 目标文件夹；null 表示用聚焦文件夹，再回退到根目录
     * @param slots    槽位索引列表（玩家当前菜单的 slot index）
     * @param names    与 slots 一一对应的显示名；可为 null 或元素为空
     */
    public static void deposit(String folderId, List<Integer> slots, List<String> names) {
        if (slots == null || slots.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>(slots.size() * 2 + 1);
        args.add(resolveTargetFolder(folderId));
        for (int i = 0; i < slots.size(); i++) {
            args.add(String.valueOf(slots.get(i)));
            String name = names != null && i < names.size() ? names.get(i) : null;
            args.add(name == null ? "" : name);
        }
        send(new DiskRequestPacket(RequestType.DEPOSIT, args));
    }

    /** 不带显示名的入库。 */
    public static void deposit(String folderId, List<Integer> slots) {
        deposit(folderId, slots, null);
    }

    /** 把磁盘里的一种物品取出到某个槽位；数量由服务端按槽位上限决定。 */
    public static void withdraw(ItemKey key, int slotIndex, long wanted) {
        if (key == null || slotIndex < 0) {
            return;
        }
        send(new DiskRequestPacket(RequestType.WITHDRAW,
                key.itemId(), key.nbt(), String.valueOf(slotIndex), String.valueOf(Math.max(1L, wanted))));
    }

    /**
     * 目标文件夹决议：显式指定 &gt; 聚焦窗口当前文件夹 &gt; 根目录。
     *
     * <p>最后那一条就是「没打开资源管理器窗口时物品进入根目录」的落地处。
     */
    public static String resolveTargetFolder(String explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        String focused = focusedFolderId;
        if (anyWindowOpen && focused != null && !focused.isEmpty()) {
            return focused;
        }
        return DiskFolder.ROOT_ID;
    }

    /** 界面导航到某文件夹时记录焦点。 */
    public static void setFocusedFolder(String folderId) {
        if (folderId != null && !folderId.isEmpty()) {
            focusedFolderId = folderId;
        }
    }

    public static String focusedFolder() {
        return focusedFolderId;
    }

    public static void setAnyWindowOpen(boolean open) {
        anyWindowOpen = open;
    }

    public static boolean anyWindowOpen() {
        return anyWindowOpen;
    }

    // ---------------------------------------------------------------- 状态接收

    /**
     * 服务端状态到达：重建所有窗口的视图。
     * 由 {@link InformationEraClientEvents} 在客户端初始化时注册到网络层。
     */
    public static void onStateUpdated(PlayerDisk mirror, boolean ok, String messageKey, String messageArg) {
        HolderLookup.Provider registries = registryProvider();
        Set<String> cut = FileExplorerWindow.cutIds();
        VirtualFileSystem view = VirtualFileSystem.project(mirror, registries, cut);
        FileExplorerWindow.applyView(view);
        if (!ok && messageKey != null && !messageKey.isEmpty()) {
            showMessage(messageKey, messageArg);
        }
    }

    /** 提示消息：优先走 AUI 的 Toast，避免依赖界面是否打开。 */
    public static void showMessage(String messageKey, String messageArg) {
        String text = messageArg == null || messageArg.isEmpty()
                ? FileExplorerText.t(messageKey)
                : FileExplorerText.t(messageKey, messageArg);
        ToastManager.show(text);
    }

    public static void showStatus(String text) {
        if (text != null && !text.isEmpty()) {
            ToastManager.show(text);
        }
    }

    static HolderLookup.Provider registryProvider() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            return mc.level.registryAccess();
        }
        if (mc.player != null) {
            return mc.player.registryAccess();
        }
        return null;
    }

    private static DiskRequestPacket withTarget(int kind, String targetFolderId, List<String> nodeIds) {
        List<String> args = new ArrayList<>(nodeIds == null ? 1 : nodeIds.size() + 1);
        args.add(resolveTargetFolder(targetFolderId));
        if (nodeIds != null) {
            args.addAll(nodeIds);
        }
        return new DiskRequestPacket(kind, args);
    }

    private static List<String> allRecycleIds() {
        PlayerDisk mirror = DiskStateHandler.mirror();
        if (mirror == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String id : mirror.childrenOf(DiskFolder.RECYCLE_ID)) {
            ids.add(id);
        }
        return ids;
    }

    private static void send(DiskRequestPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        packet.sendToServer();
    }

    private static String nz(String value, String fallback) {
        return value == null ? fallback : value;
    }

    /** 供界面判断是否已经拿到过服务端状态。 */
    public static boolean hasState() {
        return DiskStateHandler.mirror() != null;
    }
}
