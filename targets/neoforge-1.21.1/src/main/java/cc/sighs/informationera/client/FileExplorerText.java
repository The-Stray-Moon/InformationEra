package cc.sighs.informationera.client;

import net.minecraft.client.resources.language.I18n;

/** 文件资源管理器的本地化文案访问器。所有用户可见文案统一走 Minecraft I18n。 */
public final class FileExplorerText {

    private FileExplorerText() {
    }

    public static String t(String key) {
        return I18n.get(key);
    }

    public static String t(String key, Object... args) {
        return I18n.get(key, args);
    }
}
