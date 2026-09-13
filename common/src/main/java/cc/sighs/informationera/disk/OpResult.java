package cc.sighs.informationera.disk;

/**
 * 磁盘操作结果。
 *
 * <p>{@code messageKey} 是语言文件键（例如 {@code informationera.msg.deposited}），
 * 因此 common 层不需要依赖任何本地化实现，由 target 侧负责翻译成玩家看到的文字。
 * {@code arg} 是可选的格式化参数（数量、名称等）。
 */
public final class OpResult {

    private final boolean ok;
    private final String messageKey;
    private final String arg;

    private OpResult(boolean ok, String messageKey, String arg) {
        this.ok = ok;
        this.messageKey = messageKey == null ? "" : messageKey;
        this.arg = arg == null ? "" : arg;
    }

    public static OpResult ok(String messageKey) {
        return new OpResult(true, messageKey, "");
    }

    public static OpResult ok(String messageKey, String arg) {
        return new OpResult(true, messageKey, arg);
    }

    public static OpResult fail(String messageKey) {
        return new OpResult(false, messageKey, "");
    }

    public static OpResult fail(String messageKey, String arg) {
        return new OpResult(false, messageKey, arg);
    }

    public boolean isOk() {
        return ok;
    }

    public String messageKey() {
        return messageKey;
    }

    public String arg() {
        return arg;
    }

    @Override
    public String toString() {
        return (ok ? "ok" : "fail") + ":" + messageKey + (arg.isEmpty() ? "" : "(" + arg + ")");
    }
}
