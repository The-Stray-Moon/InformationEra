package cc.sighs.informationera.net;

import cc.sighs.informationera.client.DiskClient;
import cc.sighs.informationera.disk.DiskCodec;
import cc.sighs.informationera.disk.PlayerDisk;
import net.minecraft.client.Minecraft;

/**
 * 客户端侧的权威状态接收端。
 *
 * <p>服务端每次操作成功后都会推一份完整的磁盘状态；这里解码成本地只读镜像，并交给
 * {@link DiskClient} 重建所有已打开窗口的视图。不做增量猜测，避免界面与服务端不一致。
 */
public final class DiskStateHandler {

    /** 当前客户端持有的磁盘镜像；从未收到过服务端状态时为 null。 */
    private static volatile PlayerDisk mirror;

    private DiskStateHandler() {
    }

    public static void apply(DiskStatePacket packet) {
        // 网络回调不保证在客户端线程上，碰 UI 前先切回客户端线程。
        Minecraft.getInstance().execute(() -> {
            PlayerDisk decoded = DiskCodec.decode(packet.payload());
            if (decoded != null) {
                mirror = decoded;
            }
            DiskClient.onStateUpdated(mirror, packet.ok(), packet.messageKey(), packet.messageArg());
        });
    }

    /** 当前镜像；未收到过服务端状态时返回 null。 */
    public static PlayerDisk mirror() {
        return mirror;
    }
}
