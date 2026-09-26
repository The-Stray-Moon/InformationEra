package cc.sighs.informationera.disk;

/**
 * 磁盘里的一个文件夹：可以嵌套，既可以装文件，也可以装子文件夹。
 *
 * <p>用 parentId 表达父子关系（不再是 key 路径拼接），因此重命名或移动文件夹
 * 不会影响任何其他条目的标识。空 parentId 表示位于磁盘根部。
 */
public final class DiskFolder {

    /** 磁盘根节点的固定 id。根节点的显示名就是玩家名字。 */
    public static final String ROOT_ID = "r_root";

    /**
     * 视图层专用的「玩家名」目录 id。
     *
     * <p>它**不在磁盘数据里**：客户端把磁盘根挂在这个虚拟目录下，于是界面的层级是
     * 「玩家名 → 磁盘 → 磁盘内容」。服务端不认识它，收到以它为目标的操作会回落到根目录。
     */
    public static final String HOST_ID = "ie_host";
    /** 回收站的固定 id。根节点下唯一一个不可重命名、不可移动的特殊文件夹。 */
    public static final String RECYCLE_ID = "recyclebin";

    /**
     * 根目录下预置的分类文件夹 id。
     *
     * <p>这四个是新建磁盘时就存在的默认分类，让玩家存进来的第一件物品就有地方可放；
     * 它们和玩家自己新建的文件夹没有区别，可以随便改名、移动、删除。
     */
    public static final String BLOCKS_ID = "blocks";
    public static final String FOOD_ID = "food";
    public static final String TOOLS_ID = "tools";
    public static final String WEAPONS_ID = "weapons";

    /** 文件夹嵌套深度上限，防止构造出过深的树。 */
    public static final int MAX_DEPTH = 64;

    /** 预置分类文件夹的 id 列表，顺序即默认显示顺序。 */
    public static final String[] PRESET_IDS = {BLOCKS_ID, FOOD_ID, TOOLS_ID, WEAPONS_ID};

    /**
     * 预置文件夹的英文默认名。
     *
     * <p>磁盘里存的是这个字面名；客户端显示时会按 id 换成玩家语言的名字。
     * 如果玩家把文件夹改名了（名字不再等于这个默认值），就尊重玩家的命名。
     */
    public static String presetDefaultName(String id) {
        if (BLOCKS_ID.equals(id)) {
            return "Blocks";
        }
        if (FOOD_ID.equals(id)) {
            return "Food";
        }
        if (TOOLS_ID.equals(id)) {
            return "Tools";
        }
        if (WEAPONS_ID.equals(id)) {
            return "Weapons";
        }
        return null;
    }

    /** 预置文件夹的本地化键；非预置文件夹返回 null。 */
    public static String presetTranslationKey(String id) {
        if (BLOCKS_ID.equals(id)) {
            return "informationera.folder.blocks";
        }
        if (FOOD_ID.equals(id)) {
            return "informationera.folder.food";
        }
        if (TOOLS_ID.equals(id)) {
            return "informationera.folder.tools";
        }
        if (WEAPONS_ID.equals(id)) {
            return "informationera.folder.weapons";
        }
        return null;
    }

    /** 是否是预置分类文件夹。 */
    public static boolean isPreset(String id) {
        return presetDefaultName(id) != null;
    }

    private final String id;
    private String name;
    private String parentId;
    private long createdAt;
    private long updatedAt;

    public DiskFolder(String id, String name, String parentId, long createdAt, long updatedAt) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("folder id must not be empty");
        }
        this.id = id;
        this.name = name == null ? "" : name;
        // 根节点自身没有父节点。
        this.parentId = ROOT_ID.equals(id) || parentId == null || parentId.isEmpty() ? null : parentId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void rename(String newName) {
        if (newName != null && !newName.isEmpty()) {
            this.name = newName;
        }
    }

    public String parentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId == null || parentId.isEmpty() ? null : parentId;
    }

    public boolean isRoot() {
        return ROOT_ID.equals(id);
    }

    public boolean isRecycleBin() {
        return RECYCLE_ID.equals(id);
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

    @Override
    public String toString() {
        return "DiskFolder{" + id + " " + name + " @" + parentId + "}";
    }
}
