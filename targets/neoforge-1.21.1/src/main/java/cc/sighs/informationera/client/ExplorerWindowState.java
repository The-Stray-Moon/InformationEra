package cc.sighs.informationera.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 资源管理器窗口的独立持久化，落盘在 {@code config/informationera-window.json}。
 *
 * <p>设计文档要求窗口“记忆最近位置”。这里只保存最近一个窗口的正常尺寸/位置和最大化
 * 状态：重新开窗、重启客户端后新窗口从这份记录恢复。标签页、当前目录、导航历史等会话
 * 内容一律不保存。
 *
 * <p>刻意独立于 {@link ExplorerPreferences}：那边是跳转列表偏好（游戏配置系统），
 * 窗口矩形是纯界面几何，混进同一份配置会把两种数据绑死在一次重载里。
 *
 * <p>写入发生在玩家完成一次拖拽/缩放、切换最大化或关闭窗口时，频率很低；
 * 读取失败（文件缺失、损坏、旧格式）一律回退到默认居中窗口。
 */
final class ExplorerWindowState {

    private static final Logger LOGGER = LoggerFactory.getLogger("informationera/window");
    private static final String FILE_NAME = "informationera-window.json";
    private static final int FORMAT_VERSION = 1;

    private static ExplorerWindowGeometry.Frame frame;
    private static boolean maximized;
    private static boolean loaded;
    private static boolean writeFailed;

    private ExplorerWindowState() {
    }

    /** 最近一个窗口的正常矩形；没有记录时返回 null。 */
    static ExplorerWindowGeometry.Frame frame() {
        load();
        return frame;
    }

    /** 最近一个窗口关闭时是否处于最大化。 */
    static boolean maximized() {
        load();
        return maximized;
    }

    /**
     * 记住窗口的正常矩形与最大化状态。
     *
     * <p>窗口最大化时传入的是**还原后**的矩形，因此取消最大化仍然回到原来的位置。
     */
    static void remember(ExplorerWindowGeometry.Frame normalFrame, boolean isMaximized) {
        if (normalFrame == null || !isValid(normalFrame)) {
            return;
        }
        load();
        frame = normalFrame;
        maximized = isMaximized;
        save();
    }

    /** 磁盘上的记录文件路径，日志与排障用。 */
    static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    private static boolean isValid(ExplorerWindowGeometry.Frame value) {
        return Double.isFinite(value.x()) && Double.isFinite(value.y())
                && Double.isFinite(value.width()) && Double.isFinite(value.height())
                && value.width() > 0 && value.height() > 0;
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = path();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("version") && root.get("version").getAsInt() > FORMAT_VERSION) {
                // 新版本写的文件：按未知格式处理，不覆盖；等本版本真正保存时再重写。
                LOGGER.warn("Ignoring window state {}: format version {} is newer than {}", file,
                        root.get("version").getAsInt(), FORMAT_VERSION);
                return;
            }
            ExplorerWindowGeometry.Frame value = new ExplorerWindowGeometry.Frame(
                    root.get("x").getAsDouble(),
                    root.get("y").getAsDouble(),
                    root.get("width").getAsDouble(),
                    root.get("height").getAsDouble());
            if (!isValid(value)) {
                return;
            }
            frame = value;
            maximized = root.has("maximized") && root.get("maximized").getAsBoolean();
        } catch (RuntimeException | IOException malformed) {
            // 文件损坏或字段缺失：不阻止开窗，按默认居中处理，下次保存会覆盖。
            LOGGER.warn("Could not read window state {}: {}", file, malformed.toString());
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("x", frame.x());
        root.addProperty("y", frame.y());
        root.addProperty("width", frame.width());
        root.addProperty("height", frame.height());
        root.addProperty("maximized", maximized);
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            writeFailed = false;
        } catch (IOException failure) {
            if (!writeFailed) {
                writeFailed = true;
                LOGGER.warn("Could not save window state {}: {}", file, failure.toString());
            }
        }
    }
}
