package cc.sighs.informationera.net;

import cc.sighs.informationera.disk.DiskCodec;
import cc.sighs.informationera.disk.DiskService;
import cc.sighs.informationera.disk.ItemKey;
import cc.sighs.informationera.disk.OpResult;
import cc.sighs.informationera.disk.PlayerDisk;
import cc.sighs.informationera.item.StorageCardItem;
import cc.sighs.informationera.server.PlayerDiskStore;
import cc.sighs.informationera.util.DeviceSlots;
import cc.sighs.informationera.util.DiskAccess;
import cc.sighs.informationera.util.ItemStacks;
import com.sighs.apricityui.network.api.INetworkContext;
import com.sighs.apricityui.network.api.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端业务入口：把客户端的意图翻译成对玩家磁盘的变更，然后回推权威状态。
 *
 * <p>这里是唯一可以改动物品库存的地方。客户端发来的参数一律重新校验（归属、数量、
 * 目标是否存在、槽位是否可放置），校验不过只回错误消息、不动数据。
 *
 * <p>磁盘不按玩家分片，而是按**设备里那张储存卡的 uuid** 分片：插了哪张卡，看到的就是
 * 哪块盘。因此除了换槽（插卡、装卸模块）和状态查询，所有请求都要求设备里插着卡。
 */
public final class DiskRequestDispatcher {

    /** 插卡区的槽位标识。 */
    private static final String SLOT_CARD = "card";
    /** 功能模块区槽位标识的前缀，后面直接跟槽位号。 */
    private static final String SLOT_MODULE = "module";

    private DiskRequestDispatcher() {
    }

    public static void handle(DiskRequestPacket packet, INetworkContext context) {
        ServerPlayer player = context.sender();
        if (player == null) {
            // 客户端误收到 C2S 包，忽略。
            return;
        }
        MinecraftServer server = player.getServer();
        PlayerDiskStore store = PlayerDiskStore.get(server);
        if (store == null) {
            reply(player, null, null, OpResult.fail("informationera.msg.no_disk"));
            return;
        }
        DeviceSlots device = store.deviceOf(player.getUUID());
        OpResult result;
        try {
            result = dispatch(packet, player, store, device);
        } catch (RuntimeException e) {
            // 单个坏请求不应该让界面彻底失去响应。
            result = OpResult.fail("informationera.msg.internal_error", e.getClass().getSimpleName());
        }
        PlayerDisk disk = null;
        // STATUS 只回设备插槽：切界面就会发一次，不该每次都把整棵树推一遍。
        if (result.isOk() && packet.kind() != RequestType.STATUS && !device.card().isEmpty()) {
            disk = resolveDisk(player, store, device);
            // 把磁盘当前的名字同步到卡上：tooltip 上显示的名字要跟着磁盘改名走。
            StorageCardItem.applyDiskName(device.card(), disk.diskName());
            disk.purgeExpiredRecycleEntries(System.currentTimeMillis());
        }
        if (result.isOk()) {
            store.markPlayerDirty(player.getUUID());
        }
        reply(player, disk, store, result);
    }

    /**
     * 从设备里的卡解析出磁盘。
     *
     * <p>卡还没绑 uuid 就现分一个；玩家名下的旧盘（老存档里按玩家分片的那种）顺手挪到这张卡上，
     * 这样老玩家插第一张卡时看到的是自己原来的盘，而不是一个空盘。
     */
    private static PlayerDisk resolveDisk(ServerPlayer player, PlayerDiskStore store, DeviceSlots device) {
        UUID cardId = StorageCardItem.ensureDiskId(device.card());
        store.adoptLegacyDisk(player.getUUID(), cardId);
        return store.diskOfCard(cardId, StorageCardItem.defaultDiskName(cardId));
    }

    /** 哪些请求属于「物品 ↔ 信息」的转换，需要量子坍缩反应炉。 */
    private static boolean isConversion(int kind) {
        return kind == RequestType.DEPOSIT
                || kind == RequestType.WITHDRAW
                || kind == RequestType.WITHDRAW_TO_INVENTORY;
    }

    private static OpResult dispatch(DiskRequestPacket packet, ServerPlayer player,
                                     PlayerDiskStore store, DeviceSlots device) {
        List<String> args = packet.args() == null ? List.of() : packet.args();
        // 「玩家名」是客户端视图造的虚拟目录，服务端不认识它：以它为目标的操作一律落到根目录。
        if (!args.isEmpty() && cc.sighs.informationera.disk.DiskFolder.HOST_ID.equals(args.get(0))) {
            List<String> normalized = new ArrayList<>(args);
            normalized.set(0, cc.sighs.informationera.disk.DiskFolder.ROOT_ID);
            args = normalized;
        }
        // 换槽与状态查询是仅有的两个「没插卡也要能用」的请求——不然第一张卡永远插不进去。
        if (packet.kind() == RequestType.SWAP_SLOT) {
            return swapSlot(player, store, device, args);
        }
        if (packet.kind() == RequestType.STATUS) {
            return OpResult.ok("informationera.msg.opened");
        }
        if (device.card().isEmpty()) {
            return OpResult.fail("informationera.msg.no_card");
        }
        // 虚实转换要先有反应炉：没有它，磁盘只能浏览，物品坍缩不进去也还原不出来。
        if (isConversion(packet.kind()) && !DiskAccess.canConvert(player)) {
            return OpResult.fail("informationera.msg.no_reactor");
        }
        PlayerDisk disk = resolveDisk(player, store, device);
        switch (packet.kind()) {
            case RequestType.OPEN:
                return OpResult.ok("informationera.msg.opened");
            case RequestType.CREATE_FOLDER:
                return disk.createFolder(arg(args, 0, disk.defaultDropFolderId()), arg(args, 1, "New folder"));
            case RequestType.RENAME:
                return disk.rename(arg(args, 0, ""), arg(args, 1, ""));
            case RequestType.RENAME_DISK:
                return disk.renameDisk(arg(args, 0, ""));
            case RequestType.MOVE:
                return disk.transfer(tail(args), arg(args, 0, ""), false);
            case RequestType.COPY:
                return disk.transfer(tail(args), arg(args, 0, ""), true);
            case RequestType.TO_RECYCLE:
                return disk.toRecycleBin(args);
            case RequestType.RESTORE:
                return disk.restoreFromRecycleBin(args);
            case RequestType.PURGE:
                return disk.purge(args);
            case RequestType.EMPTY_RECYCLE:
                return disk.emptyRecycleBin();
            case RequestType.DEPOSIT:
                return deposit(player, disk, device, args);
            case RequestType.WITHDRAW:
                return withdraw(player, disk, args);
            case RequestType.WITHDRAW_TO_INVENTORY:
                return withdrawToInventory(player, disk, args);
            default:
                return OpResult.fail("informationera.msg.unknown_request", String.valueOf(packet.kind()));
        }
    }

    // ---------------------------------------------------------------- 换槽

    /**
     * 换槽：把设备某个插槽与玩家物品栏的一个槽位整体互换。
     *
     * <p>参数：{@code slotId}（{@code card} 或 {@code module0} / {@code module1}）、{@code inventoryIndex}。
     * 索引用的是玩家物品栏自己的容器下标（0–35 是快捷栏与主物品栏），不是当前容器菜单的槽位
     * 下标——开始菜单是独立 Overlay，拿不到菜单下标，只能按物品栏编号来。
     */
    private static OpResult swapSlot(ServerPlayer player, PlayerDiskStore store,
                                     DeviceSlots device, List<String> args) {
        String slotId = arg(args, 0, "");
        boolean cardSlot = SLOT_CARD.equals(slotId);
        int moduleIndex = cardSlot ? -1 : moduleIndexOf(slotId);
        if (!cardSlot && moduleIndex < 0) {
            return OpResult.fail("informationera.msg.slot_rejected");
        }
        int inventoryIndex = parseInt(arg(args, 1, "-1"), -1);
        Inventory inventory = player.getInventory();
        if (inventoryIndex < 0 || inventoryIndex >= inventory.getContainerSize()) {
            return OpResult.fail("informationera.msg.slot_rejected");
        }
        ItemStack incoming = inventory.getItem(inventoryIndex);
        // 插卡区只收储存卡；模块区先不限制，留给你以后放任何东西。
        if (cardSlot && !incoming.isEmpty() && !(incoming.getItem() instanceof StorageCardItem)) {
            return OpResult.fail("informationera.msg.not_a_card");
        }
        ItemStack current = cardSlot ? device.card() : device.module(moduleIndex);
        if (cardSlot) {
            device.setCard(incoming);
        } else {
            device.setModule(moduleIndex, incoming);
        }
        inventory.setItem(inventoryIndex, current);
        inventory.setChanged();
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != null) {
            menu.broadcastChanges();
        }
        store.markPlayerDirty(player.getUUID());
        return OpResult.ok("informationera.msg.slot_swapped");
    }

    private static int moduleIndexOf(String slotId) {
        if (slotId == null || !slotId.startsWith(SLOT_MODULE)) {
            return -1;
        }
        int index = parseInt(slotId.substring(SLOT_MODULE.length()), -1);
        return index >= 0 && index < DeviceSlots.MODULE_SLOTS ? index : -1;
    }

    // ---------------------------------------------------------------- 入库

    /**
     * 入库参数：{@code folderId}，随后是若干槽位索引。
     *
     * <p>客户端只报「哪些槽位」，数量与物品内容一律由服务端从玩家背包重新读取，
     * 因此伪造数量没有意义。
     */
    private static OpResult deposit(ServerPlayer player, PlayerDisk disk, DeviceSlots device, List<String> args) {
        String folderId = arg(args, 0, disk.defaultDropFolderId());
        if (folderId == null || !disk.exists(folderId)) {
            folderId = disk.defaultDropFolderId();
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return OpResult.fail("informationera.msg.no_container");
        }

        // 第一步：只读地收集，不碰任何槽位。
        // 参数从下标 1 起是「槽位索引, 显示名」成对出现。
        List<DiskService.Deposit> deposits = new ArrayList<>();
        List<Slot> sources = new ArrayList<>();
        for (int i = 1; i < args.size(); i += 2) {
            int slotIndex = parseInt(args.get(i), -1);
            if (slotIndex < 0 || slotIndex >= menu.slots.size()) {
                continue;
            }
            String displayName = i + 1 < args.size() ? args.get(i + 1) : null;
            Slot slot = menu.getSlot(slotIndex);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !slot.mayPickup(player)) {
                continue;
            }
            ItemKey key = ItemStacks.keyOf(stack, player.registryAccess());
            if (key == null) {
                continue;
            }
            deposits.add(new DiskService.Deposit(key, stack.getCount(), displayName));
            sources.add(slot);
        }
        if (deposits.isEmpty()) {
            return OpResult.fail("informationera.msg.nothing_to_deposit");
        }

        // 容量校验：储存空间由储存卡提供。整批超出就整批拒绝，不做部分入库——否则客户端
        // 会以为「都存进去了」，而服务端只收了一部分，两边对不上。
        // 设备里插着的那张卡也算额度：卡插进去就不在物品栏里了。
        long incoming = 0L;
        for (DiskService.Deposit entry : deposits) {
            incoming += entry.amount();
        }
        long used = disk.totalQuantity();
        long capacity = DiskAccess.capacity(player, device.card().getCount());
        if (used + incoming > capacity) {
            return OpResult.fail("informationera.msg.disk_full", used + " / " + capacity);
        }

        // 第二步：先全部入账。只有这一步完全成功，才去扣槽位——
        // 反过来做的话，中途失败就需要「还回物品」，稍有不慎就会复制。
        OpResult result = DiskService.depositAll(disk, folderId, deposits);
        if (!result.isOk()) {
            return result;
        }

        // 第三步：入账成功后扣空来源槽位。
        for (Slot slot : sources) {
            // 取走的数量就是当初读到的槽内数量（最多一堆），直接清空该槽。
            slot.remove(slot.getItem().getCount());
            slot.setChanged();
        }
        menu.broadcastChanges();
        return result;
    }

    // ---------------------------------------------------------------- 取出

    /**
     * 取出参数：{@code itemId}, {@code nbt}, {@code slotIndex}, {@code wanted}。
     *
     * <p>按目标槽位的上限取出（不可堆叠物品一次一个），放不下的部分留在磁盘。
     */
    private static OpResult withdraw(ServerPlayer player, PlayerDisk disk, List<String> args) {
        String itemId = arg(args, 0, "");
        String nbt = arg(args, 1, "");
        int slotIndex = parseInt(arg(args, 2, "-1"), -1);
        long wanted = parseLong(arg(args, 3, "1"), 1L);
        if (itemId.isEmpty() || wanted <= 0L) {
            return OpResult.fail("informationera.msg.invalid_amount");
        }
        ItemKey key = new ItemKey(itemId, nbt);

        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || slotIndex < 0 || slotIndex >= menu.slots.size()) {
            return OpResult.fail("informationera.msg.no_container");
        }
        Slot slot = menu.getSlot(slotIndex);
        if (!slot.isActive()) {
            return OpResult.fail("informationera.msg.slot_rejected");
        }

        ItemStack probe = ItemStacks.stackOf(key, 1L, player.registryAccess());
        if (probe.isEmpty()) {
            return OpResult.fail("informationera.msg.unknown_item", itemId);
        }
        // 用真实的 1 个装校验槽位是否接受，避免拿空栈去问导致误判。
        if (!slot.mayPlace(probe)) {
            return OpResult.fail("informationera.msg.slot_rejected");
        }

        ItemStack existing = slot.getItem();
        // 目标槽必须是空的，或者已经装着同一种物品。
        if (!existing.isEmpty()) {
            ItemKey existingKey = ItemStacks.keyOf(existing, player.registryAccess());
            if (existingKey == null || !existingKey.equals(key)) {
                return OpResult.fail("informationera.msg.slot_occupied");
            }
        }
        int max = slot.getMaxStackSize(existing.isEmpty() ? probe : existing);
        int room = Math.max(0, max - existing.getCount());
        if (room <= 0) {
            return OpResult.fail("informationera.msg.slot_full");
        }
        long want = Math.min(wanted, room);

        long taken = disk.withdraw(key, want);
        if (taken <= 0L) {
            return OpResult.fail("informationera.msg.not_enough", key.itemId());
        }
        ItemStack produced = ItemStacks.stackOf(key, taken, player.registryAccess());
        if (produced.isEmpty()) {
            // 兜底：物品构造失败就把数量还回磁盘。
            disk.deposit(null, key, taken);
            return OpResult.fail("informationera.msg.unknown_item", itemId);
        }
        if (existing.isEmpty()) {
            slot.set(produced);
        } else {
            existing.grow((int) taken);
            slot.set(existing);
        }
        slot.setChanged();
        menu.broadcastChanges();
        return OpResult.ok("informationera.msg.withdrawn", String.valueOf(taken));
    }

    /**
     * 取出到玩家物品栏：参数 {@code itemId}, {@code nbt}，可选 {@code wanted}。
     *
     * <p>用原版的 {@code Player#addItem(ItemStack)} 完成插入——它内部会**先并入已有堆叠、
     * 再找空槽**，合并行为与拾取物品完全一致，不需要自己遍历槽位。
     *
     * <p>顺序上**先试着放进物品栏、再按实际放入的数量从磁盘扣**，而不是反过来：
     * {@code withdraw} 在把库存取空时会一并移除所有引用它的文件（文件是库存的别名），
     * 若先取后放、放不下再退回，退回时会因「已无文件」而在根目录新建一个——
     * 物品就会从原文件夹跳走，多个别名也会被合并掉。先试放就不存在退回这一步。
     *
     * <p>数量默认取该物品的一个堆叠上限（和拖到容器槽位时「按槽位上限取」是同一套语义）。
     */
    private static OpResult withdrawToInventory(ServerPlayer player, PlayerDisk disk, List<String> args) {
        String itemId = arg(args, 0, "");
        String nbt = arg(args, 1, "");
        if (itemId.isEmpty()) {
            return OpResult.fail("informationera.msg.invalid_amount");
        }
        ItemKey key = new ItemKey(itemId, nbt);

        ItemStack probe = ItemStacks.stackOf(key, 1L, player.registryAccess());
        if (probe.isEmpty()) {
            return OpResult.fail("informationera.msg.unknown_item", itemId);
        }
        int maxStack = Math.max(1, probe.getMaxStackSize());

        long available = disk.quantityOf(key);
        if (available <= 0L) {
            return OpResult.fail("informationera.msg.not_enough", key.itemId());
        }
        // 一次取出一个堆叠的量；不可堆叠物品就是 1。
        long wanted = parseLong(arg(args, 2, "0"), 0L);
        if (wanted <= 0L) {
            wanted = maxStack;
        }
        wanted = Math.min(Math.min(wanted, maxStack), available);

        ItemStack produced = ItemStacks.stackOf(key, wanted, player.registryAccess());
        if (produced.isEmpty()) {
            return OpResult.fail("informationera.msg.unknown_item", itemId);
        }
        produced.setCount((int) wanted);

        // 试着放进物品栏：addItem 会就地改写传入的栈，调用后它的数量就是没能放进去的部分。
        player.addItem(produced);
        long moved = wanted - produced.getCount();
        if (moved <= 0L) {
            return OpResult.fail("informationera.msg.inventory_full");
        }

        // 只从磁盘扣掉真正进了物品栏的数量。
        // 上面已确认 available >= wanted >= moved，所以这里必定取足；取不足则说明库存被
        // 并发改动过，此时把多发出去的部分丢掉是不可能的，只能记下来——但同一 tick 内不会发生。
        long taken = disk.withdraw(key, moved);
        return OpResult.ok("informationera.msg.withdrawn", String.valueOf(taken));
    }

    // ---------------------------------------------------------------- 工具

    private static void reply(ServerPlayer player, PlayerDisk disk, PlayerDiskStore store, OpResult result) {
        String payload = disk == null ? "" : DiskCodec.encode(disk);
        // 设备插槽每次一并回推：客户端靠它渲染开始菜单、并决定资源管理器按钮是否出现。
        String device = store == null ? "" : store.deviceOf(player.getUUID()).encode(player.registryAccess());
        NetworkManager.sendToPlayer(DiskStatePacket.of(payload, device, result.isOk(),
                result.messageKey(), result.arg()), player);
    }

    private static List<String> tail(List<String> args) {
        return args.isEmpty() ? List.of() : args.subList(1, args.size());
    }

    private static String arg(List<String> args, int index, String fallback) {
        return index >= 0 && index < args.size() && args.get(index) != null ? args.get(index) : fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
