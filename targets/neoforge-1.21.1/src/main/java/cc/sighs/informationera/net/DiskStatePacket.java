package cc.sighs.informationera.net;

import com.sighs.apricityui.network.api.INetworkContext;
import com.sighs.apricityui.network.api.INetworkPacket;
import com.sighs.apricityui.network.api.NetworkPacket;
import com.sighs.apricityui.network.api.Side;

/**
 * 服务端 → 客户端：磁盘的权威状态。
 *
 * <p>{@code payload} 是 {@code DiskCodec} 编码后的行式文本，客户端解码成只读镜像。
 * 任何变更成功后服务端都会回一份这样的包，客户端据此整体刷新，不做增量猜测。
 */
@NetworkPacket(modId = "informationera", id = "disk_state", side = Side.CLIENT, chunkThreshold = 24000)
public record DiskStatePacket(String payload, boolean ok, String messageKey, String messageArg)
        implements INetworkPacket<DiskStatePacket> {

    public static DiskStatePacket of(String payload, boolean ok, String messageKey, String messageArg) {
        return new DiskStatePacket(payload == null ? "" : payload, ok,
                messageKey == null ? "" : messageKey, messageArg == null ? "" : messageArg);
    }

    @Override
    public void handle(INetworkContext context) {
        DiskStateHandler.apply(this);
    }
}
