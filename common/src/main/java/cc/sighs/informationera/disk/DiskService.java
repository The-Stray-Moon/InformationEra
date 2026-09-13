package cc.sighs.informationera.disk;

import java.util.ArrayList;
import java.util.List;

/**
 * 磁盘的业务规则层：把「玩家看到的动作」翻译成对 {@link PlayerDisk} 的具体变更。
 *
 * <p>与 {@code PlayerDisk} 的分工：{@code PlayerDisk} 只管数据一致性（父子关系、引用计数、
 * 库存不悬空），本类管业务语义（应该放在哪个文件夹、一次取多少、名称怎么取）。所有方法
 * 都不依赖 Minecraft 类型：物品以 {@link ItemKey} + 数量传入，因此服务端可以从任何物品源
 * （槽位、容器、实体）调用它们。
 */
public final class DiskService {

    private DiskService() {
    }

    /**
     * 入库一批物品。每次调用只在必要时新建文件，其余数量并入已有库存。
     *
     * @param disk     目标磁盘
     * @param folderId 归属文件夹；null 或无效时回退到已有文件所在文件夹，再回退到根目录
     * @param entries  待入库的物品；同一次调用里出现的同种物品会被合并
     */
    public static OpResult depositAll(PlayerDisk disk, String folderId, List<Deposit> entries) {
        if (disk == null) {
            return OpResult.fail("informationera.msg.no_disk");
        }
        if (entries == null || entries.isEmpty()) {
            return OpResult.fail("informationera.msg.nothing_to_deposit");
        }
        long total = 0L;
        int kinds = 0;
        for (Deposit entry : entries) {
            if (entry == null || entry.key() == null || entry.amount() <= 0L) {
                continue;
            }
            OpResult result = disk.deposit(folderId, entry.key(), entry.amount());
            if (!result.isOk()) {
                return result;
            }
            total += entry.amount();
            kinds++;
        }
        if (total <= 0L) {
            return OpResult.fail("informationera.msg.nothing_to_deposit");
        }
        return OpResult.ok("informationera.msg.deposited_kinds",
                total + "|" + kinds);
    }

    /**
     * 取出物品：从磁盘库存里扣减，返回实际取出的数量。
     *
     * <p>调用方负责把取出的数量放进目标（槽位/容器）。当库存在扣减后归零时，物品会从库存
     * 消失，但引用它的文件仍然保留——文件显示的数量变为 0，代表「这个物品没了」。
     */
    public static long withdraw(PlayerDisk disk, ItemKey key, long wanted) {
        if (disk == null || key == null || wanted <= 0L) {
            return 0L;
        }
        return disk.withdraw(key, wanted);
    }

    /**
     * 校验一批「取出请求」在当前库存下最多能取出多少，用于给客户端预先反馈。
     * 返回与请求同序的可取出数量。
     */
    public static long[] clampWithdrawals(PlayerDisk disk, List<Withdraw> requests) {
        if (requests == null || requests.isEmpty()) {
            return new long[0];
        }
        long[] result = new long[requests.size()];
        // 同一种物品可能在一次请求里出现多次，需要扣着虚拟余额算。
        List<ItemKey> seen = new ArrayList<>();
        List<Long> used = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            Withdraw request = requests.get(i);
            if (request == null || request.key() == null || request.amount() <= 0L) {
                result[i] = 0L;
                continue;
            }
            long available = disk.quantityOf(request.key());
            int index = seen.indexOf(request.key());
            if (index < 0) {
                seen.add(request.key());
                used.add(0L);
                index = seen.size() - 1;
            }
            long remaining = Math.max(0L, available - used.get(index));
            long taken = Math.min(remaining, request.amount());
            used.set(index, used.get(index) + taken);
            result[i] = taken;
        }
        return result;
    }

    /** 一次入库请求：某种物品、若干数量。 */
    public static final class Deposit {
        private final ItemKey key;
        private final long amount;

        public Deposit(ItemKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        public ItemKey key() {
            return key;
        }

        public long amount() {
            return amount;
        }
    }

    /** 一次取出请求：某种物品、希望取出多少。 */
    public static final class Withdraw {
        private final ItemKey key;
        private final long amount;

        public Withdraw(ItemKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        public ItemKey key() {
            return key;
        }

        public long amount() {
            return amount;
        }
    }
}
