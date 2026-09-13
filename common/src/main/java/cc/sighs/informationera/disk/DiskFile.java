package cc.sighs.informationera.disk;

/**
 * 磁盘里的一个「文件」：它是对某种物品的一个命名引用。
 *
 * <p>文件本身不持有数量——quantity 始终从 {@link PlayerDisk#quantityOf(ItemKey)} 读取。
 * 因此同一种物品可以在任意多个文件夹里各有一个文件，而所有文件看到的是同一份数量；
 * 任何一处数量变化都会同时反映到全部文件上。
 */
public final class DiskFile {

    private final String id;
    private final ItemKey key;
    private String name;
    private long createdAt;
    private long updatedAt;

    public DiskFile(String id, ItemKey key, String name, long createdAt, long updatedAt) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("file id must not be empty");
        }
        if (key == null) {
            throw new IllegalArgumentException("file key must not be null");
        }
        this.id = id;
        this.key = key;
        this.name = name == null || name.isEmpty() ? defaultName(key) : name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 未指定名称时使用物品 id 的路径段作为默认名，例如 {@code minecraft:iron_ingot} -> {@code iron_ingot}。 */
    public static String defaultName(ItemKey key) {
        String id = key.itemId();
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.isEmpty() ? id : path;
    }

    public String id() {
        return id;
    }

    public ItemKey key() {
        return key;
    }

    public String name() {
        return name;
    }

    public void rename(String newName) {
        if (newName != null && !newName.isEmpty()) {
            this.name = newName;
        }
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void touch(long now) {
        this.updatedAt = now;
    }

    /** 深拷贝（id 保持不变，调用方负责在需要时改 id）。 */
    public DiskFile copy() {
        return new DiskFile(id, key, name, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "DiskFile{" + id + " " + name + " -> " + key + "}";
    }
}
