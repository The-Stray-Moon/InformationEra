package cc.sighs.informationera.net;

import cc.sighs.informationera.client.DiskClient;
import cc.sighs.informationera.disk.DiskCodec;
import cc.sighs.informationera.disk.PlayerDisk;
import cc.sighs.informationera.util.DeviceSlots;
import net.minecraft.client.Minecraft;

/**
 * 客户端侧的权威状态接收端。
 *
 * <p>服务端每次操作成功后都会推一份完整状态：磁盘镜像 + 设备插槽。这里解码成本地只读镜像，
 * 再交给 {@link DiskClient} 重建所有已打开窗口的视图。不做增量猜测，避免界面与服务端不一致。
 */
public final class DiskStateHandler {

    /** 当前客户端持有的磁盘镜像；没插卡或从未收到过状态时为 null。 */
    private static volatile PlayerDisk mirror;
    /** 当前客户端持有的设备插槽镜像（插卡区的卡 + 功能模块）。 */
    private static volatile DeviceSlots device = new DeviceSlots();

    private DiskStateHandler() {
    }

    public static void apply(DiskStatePacket packet) {
        // 网络回调不保证在客户端线程上，碰 UI 前先切回客户端线程。
        Minecraft.getInstance().execute(() -> {
            device = DeviceSlots.decode(packet.device(), DiskClient.registryProvider());
            String payload = DiskPayload.decode(packet.payload());
            if (payload == null) {
                // 损坏、截断或超限的快照不能覆盖上一份有效镜像，也不能让界面误以为已更新。
                DiskClient.onStateUpdated(mirror, false,
                        "informationera.msg.disk_state_invalid", "");
                return;
            }
            PlayerDisk decoded = DiskCodec.decode(payload);
            if (decoded == null && !payload.isEmpty()) {
                DiskClient.onStateUpdated(mirror, false,
                        "informationera.msg.disk_state_invalid", "");
                return;
            }
            if (decoded != null) {
                mirror = decoded;
            } else if (device.card().isEmpty()) {
                // 没插卡：镜像清掉，界面不该还挂着上一张卡的内容。
                mirror = null;
            }
            // 插着卡但 payload 为空 = 这只是一次设备状态查询，磁盘镜像保持不动。
            DiskClient.onStateUpdated(mirror, packet.ok(), packet.messageKey(), packet.messageArg());
        });
    }

    /** 当前镜像；没插卡或未收到过服务端状态时返回 null。 */
    public static PlayerDisk mirror() {
        return mirror;
    }

    /** 设备插槽镜像：开始菜单据此渲染插卡区与功能模块区。 */
    public static DeviceSlots device() {
        return device;
    }

    /** 设备里是否插着储存卡：资源管理器按钮与开窗都看它。 */
    public static boolean cardInserted() {
        return !device.card().isEmpty();
    }
}
