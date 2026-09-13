package cc.sighs.informationera.server;

import cc.sighs.informationera.disk.DiskCodec;
import cc.sighs.informationera.disk.PlayerDisk;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 世界级持久化的磁盘仓库：按玩家 UUID 分片保存各自的 {@link PlayerDisk}。
 *
 * <p>放在世界存档而不是玩家 .dat 里，好处是服务器权威、不受玩家数据膨胀影响，
 * 后续做共享盘或多 Mod 协作时也容易复用。磁盘内容以 {@link DiskCodec} 的行式文本
 * 存进一个字符串标签，因此不需要为每种物品/文件写 NBT 结构。
 */
public final class PlayerDiskStore extends SavedData {

    private static final String DATA_NAME = "informationera_data";
    private static final String TAG_PLAYERS = "players";

    private final Map<UUID, PlayerDisk> disks = new HashMap<>();
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

    /** 取某个玩家的磁盘；不存在时按需创建，根目录以玩家名字命名。 */
    public PlayerDisk diskOf(UUID playerId, String playerName) {
        PlayerDisk disk = disks.get(playerId);
        if (disk == null) {
            String name = playerName == null || playerName.isEmpty() ? "Information Disk" : playerName;
            disk = new PlayerDisk(name);
            disks.put(playerId, disk);
            markPlayerDirty(playerId);
        }
        return disk;
    }

    /** 磁盘内容发生了变化，标记待保存。 */
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
        CompoundTag players = new CompoundTag();
        for (Map.Entry<UUID, PlayerDisk> entry : disks.entrySet()) {
            players.putString(entry.getKey().toString(), DiskCodec.encode(entry.getValue()));
        }
        tag.put(TAG_PLAYERS, players);
        return tag;
    }

    private static PlayerDiskStore load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerDiskStore store = new PlayerDiskStore();
        CompoundTag players = tag.getCompound(TAG_PLAYERS);
        for (String key : players.getAllKeys()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                // UUID 无法解析的记录直接跳过。
                continue;
            }
            PlayerDisk disk = DiskCodec.decode(players.getString(key));
            if (disk != null) {
                store.disks.put(id, disk);
            }
        }
        return store;
    }
}
