package cc.sighs.informationera.net;

import cc.sighs.informationera.disk.DiskCodec;
import cc.sighs.informationera.disk.DiskService;
import cc.sighs.informationera.disk.ItemKey;
import cc.sighs.informationera.disk.OpResult;
import cc.sighs.informationera.disk.PlayerDisk;
import cc.sighs.informationera.server.PlayerDiskStore;
import cc.sighs.informationera.util.ItemStacks;
import com.sighs.apricityui.network.api.INetworkContext;
import com.sighs.apricityui.network.api.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端业务入口：把客户端的意图翻译成对玩家磁盘的变更，然后回推权威状态。
 *
 * <p>这里是唯一可以改动物品库存的地方。客户端发来的参数一律重新校验（归属、数量、
 * 目标是否存在、槽位是否可放置），校验不过只回错误消息、不动数据。
 */
public final class DiskRequestDispatcher {

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
            reply(player, null, OpResult.fail("informationera.msg.no_disk"));
            return;
        }
        PlayerDisk disk = store.diskOf(player.getUUID(), player.getGameProfile().getName());
        OpResult result;
        try {
            result = dispatch(packet, player, disk);
        } catch (RuntimeException e) {
            // 单个坏请求不应该让界面彻底失去响应。
            result = OpResult.fail("informationera.msg.internal_error", e.getClass().getSimpleName());
        }
        if (result.isOk()) {
            disk.purgeExpiredRecycleEntries(System.currentTimeMillis());
            store.markPlayerDirty(player.getUUID());
        }
        reply(player, disk, result);
    }

    private static OpResult dispatch(DiskRequestPacket packet, ServerPlayer player, PlayerDisk disk) {
        List<String> args = packet.args() == null ? List.of() : packet.args();
        switch (packet.kind()) {
            case RequestType.OPEN:
                return OpResult.ok("informationera.msg.opened");
            case RequestType.CREATE_FOLDER:
                return disk.createFolder(arg(args, 0, disk.defaultDropFolderId()), arg(args, 1, "New folder"));
            case RequestType.RENAME:
                return disk.rename(arg(args, 0, ""), arg(args, 1, ""));
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
                return deposit(player, disk, args);
            case RequestType.WITHDRAW:
                return withdraw(player, disk, args);
            default:
                return OpResult.fail("informationera.msg.unknown_request", String.valueOf(packet.kind()));
        }
    }

    // ---------------------------------------------------------------- 入库

    /**
     * 入库参数：{@code folderId}，随后是若干槽位索引。
     *
     * <p>客户端只报「哪些槽位」，数量与物品内容一律由服务端从玩家背包重新读取，
     * 因此伪造数量没有意义。
     */
    private static OpResult deposit(ServerPlayer player, PlayerDisk disk, List<String> args) {
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

    // ---------------------------------------------------------------- 工具

    private static void reply(ServerPlayer player, PlayerDisk disk, OpResult result) {
        String payload = disk == null ? "" : DiskCodec.encode(disk);
        NetworkManager.sendToPlayer(DiskStatePacket.of(payload, result.isOk(),
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
