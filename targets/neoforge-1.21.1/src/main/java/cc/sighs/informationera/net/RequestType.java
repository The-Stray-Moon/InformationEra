package cc.sighs.informationera.net;

/**
 * 客户端 → 服务端 的请求类型常量。
 *
 * <p>所有磁盘变更都只有这一个入口：客户端只描述意图，服务端校验并执行，再把权威状态推回。
 * 因此客户端永远不持有可被篡改的状态，也无法凭空造出物品。
 */
public final class RequestType {

    /** 打开磁盘：请求整棵树与库存。 */
    public static final int OPEN = 1;
    /** 新建文件夹。 */
    public static final int CREATE_FOLDER = 2;
    /** 重命名文件或文件夹。 */
    public static final int RENAME = 3;
    /** 移动节点。 */
    public static final int MOVE = 4;
    /** 复制节点（不产生物品，只增加对同一份库存的引用）。 */
    public static final int COPY = 5;
    /** 移入回收站。 */
    public static final int TO_RECYCLE = 6;
    /** 从回收站还原。 */
    public static final int RESTORE = 7;
    /** 彻底删除（会丢弃只被这些文件引用的物品数量）。 */
    public static final int PURGE = 8;
    /** 清空回收站。 */
    public static final int EMPTY_RECYCLE = 9;
    /** 把玩家背包/容器里的物品存入磁盘。 */
    public static final int DEPOSIT = 10;
    /** 把磁盘里的物品取出到某个容器槽位。 */
    public static final int WITHDRAW = 11;
    /** 设置磁盘名称。 */
    public static final int RENAME_DISK = 12;

    private RequestType() {
    }
}
