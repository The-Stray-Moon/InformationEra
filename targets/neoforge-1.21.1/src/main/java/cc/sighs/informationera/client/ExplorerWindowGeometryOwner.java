package cc.sighs.informationera.client;

/**
 * 「最后一次被玩家主动调整几何的窗口」登记表（纯身份比较，不依赖界面，可独立测试）。
 *
 * <p>窗口持久化只保存一份矩形。多窗口时如果每个窗口关闭都写一次，关掉一个从没动过的
 * 旧窗口就会把玩家刚调好的位置覆盖掉。因此只有登记在案的窗口才允许在关闭时落盘；
 * 它关闭之后登记清空，后续关闭的窗口不再改写记录。
 */
final class ExplorerWindowGeometryOwner {

    private Object owner;

    /** 登记一个窗口为几何所有者；后登记者覆盖前者。 */
    void mark(Object window) {
        owner = window;
    }

    /** 该窗口是否就是当前所有者。 */
    boolean isOwner(Object window) {
        return window != null && owner == window;
    }

    /** 所有者自己关闭/移除后清空登记，避免之后误判。 */
    void forget(Object window) {
        if (owner == window) {
            owner = null;
        }
    }
}
