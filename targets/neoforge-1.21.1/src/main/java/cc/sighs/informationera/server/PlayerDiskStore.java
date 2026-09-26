package cc.sighs.informationera.server;

import cc.sighs.informationera.disk.DiskCodec;
import cc.sighs.informationera.disk.PlayerDisk;
import cc.sighs.informationera.util.DeviceSlots;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 世界级持久化的仓库：按**储存卡的 uuid** 分片保存各自的 {@link PlayerDisk}，另外按玩家 uuid
 * 保存每人设备里的插槽内容（{@link DeviceSlots}）。
 *
 * <p>磁盘跟卡走而不是跟玩家走：同一张卡在谁手里都是同一块盘，卡换主人不影响内容，玩家侧
 * 只有「设备里插了哪张卡」这一份状态。放在世界存档而不是玩家 .dat 里，服务器权威、不受
 * 玩家数据膨胀影响。磁盘内容以 {@link DiskCodec} 的行式文本存进字符串标签，因此不需要为
 * 每种物品/文件写 NBT 结构。
 */
public final class PlayerDiskStore extends SavedData {

    private static final String DATA_NAME = "informationera_data";
    /** 旧格式：按玩家 uuid 分片的磁盘。只读，仅用作迁移来源。 */
    private static final String TAG_LEGACY_PLAYERS = "players";
    /** 新格式：按储存卡 uuid 分片的磁盘。 */
    private static final String TAG_DISKS = "disks";
    /** 玩家设备插槽：玩家 uuid -> 编码后的插槽内容。 */
    private static final String TAG_DEVICES = "devices";

    private final Map<UUID, PlayerDisk> disks = new HashMap<>();
    /** 旧数据：玩家 uuid -> 磁盘。玩家第一次插卡时会被挪到那张卡上。 */
    private final Map<UUID, PlayerDisk> legacyDisks = new HashMap<>();
    private final Map<UUID, DeviceSlots> devices = new HashMap<>();
    /** 尚未落盘的玩家，tick 末统一保存，避免每次操作都写盘。 */
    private final Map<UUID, Boolean> dirtyFlags = new HashMap<>();

    /** 取得主世界的仓库实例。 */
    public static PlayerDiskStore get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return null;
        }
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PlayerDiskStore::new, PlayerDiskStore::load), DATA_NAME);
    }

    /** 取某张储存卡对应的磁盘；不存在时按需创建，根目录用卡给的默认名。 */
    public PlayerDisk diskOfCard(UUID cardId, String defaultName) {
        PlayerDisk disk = disks.get(cardId);
        if (disk == null) {
            String name = defaultName == null || defaultName.isEmpty() ? "Information Disk" : defaultName;
            disk = new PlayerDisk(name);
            disks.put(cardId, disk);
            setDirty();
        }
        return disk;
    }

    public boolean hasDisk(UUID cardId) {
        return cardId != null && disks.containsKey(cardId);
    }

    /**
     * 把玩家名下的旧磁盘挪到这张卡上。
     *
     * <p>只在「这张卡还没有自己的盘」且「旧盘还在」时做一次，名字保持旧盘的名字——
     * 玩家不会因为插了张卡就发现自己的盘改了名。这是老存档唯一的迁移入口。
     */
    public void adoptLegacyDisk(UUID playerId, UUID cardId) {
        if (playerId == null || cardId == null || disks.containsKey(cardId)) {
            return;
        }
        PlayerDisk legacy = legacyDisks.remove(playerId);
        if (legacy == null) {
            return;
        }
        disks.put(cardId, legacy);
        setDirty();
    }

    /** 取玩家的设备插槽；不存在时按需创建（空设备）。 */
    public DeviceSlots deviceOf(UUID playerId) {
        DeviceSlots device = devices.get(playerId);
        if (device == null) {
            device = new DeviceSlots();
            devices.put(playerId, device);
            setDirty();
        }
        return device;
    }

    /** 玩家侧的数据（磁盘内容或设备插槽）发生了变化，标记待保存。 */
    public void markPlayerDirty(UUID playerId) {
        if (playerId != null) {
            dirtyFlags.put(playerId, Boolean.TRUE);
            setDirty();
        }
    }

    /** 把所有待保存的玩家刷到数据标签里；由服务端在需要时调用。 */
    public void flush() {
        if (!dirtyFlags.isEmpty()) {
            dirtyFlags.clear();
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(TAG_DISKS, writeDisks(disks));
        tag.put(TAG_LEGACY_PLAYERS, writeDisks(legacyDisks));
        CompoundTag devicesTag = new CompoundTag();
        for (Map.Entry<UUID, DeviceSlots> entry : devices.entrySet()) {
            devicesTag.putString(entry.getKey().toString(), entry.getValue().encode(registries));
        }
        tag.put(TAG_DEVICES, devicesTag);
        return tag;
    }

    private static PlayerDiskStore load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerDiskStore store = new PlayerDiskStore();
        readDisks(tag.getCompound(TAG_DISKS), store.disks);
        readDisks(tag.getCompound(TAG_LEGACY_PLAYERS), store.legacyDisks);
        CompoundTag devicesTag = tag.getCompound(TAG_DEVICES);
        for (String key : devicesTag.getAllKeys()) {
            UUID id = parseUuid(key);
            if (id != null) {
                store.devices.put(id, DeviceSlots.decode(devicesTag.getString(key), registries));
            }
        }
        return store;
    }

    private static CompoundTag writeDisks(Map<UUID, PlayerDisk> source) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<UUID, PlayerDisk> entry : source.entrySet()) {
            tag.putString(entry.getKey().toString(), DiskCodec.encode(entry.getValue()));
        }
        return tag;
    }

    private static void readDisks(CompoundTag tag, Map<UUID, PlayerDisk> target) {
        for (String key : tag.getAllKeys()) {
            UUID id = parseUuid(key);
            if (id == null) {
                // UUID 无法解析的记录直接跳过。
                continue;
            }
            PlayerDisk disk = DiskCodec.decode(tag.getString(key));
            if (disk != null) {
                target.put(id, disk);
            }
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
