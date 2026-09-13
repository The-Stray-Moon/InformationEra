package cc.sighs.informationera.net;

import com.sighs.apricityui.network.api.INetworkContext;
import com.sighs.apricityui.network.api.INetworkPacket;
import com.sighs.apricityui.network.api.NetworkPacket;
import com.sighs.apricityui.network.api.Side;

import java.util.List;

/**
 * 客户端 → 服务端：一次磁盘操作请求。
 *
 * <p>参数统一放 {@code args} 字符串列表，按 {@link RequestType} 解释。用这种方式而不是
 * 每种操作一个包，是为了把协议面收在一处：新增操作只需加常量与一个分支，不必再动注册。
 */
@NetworkPacket(modId = "informationera", id = "disk_request", side = Side.SERVER)
public record DiskRequestPacket(int kind, List<String> args) implements INetworkPacket<DiskRequestPacket> {

    public DiskRequestPacket(int kind) {
        this(kind, List.of());
    }

    public DiskRequestPacket(int kind, String... args) {
        this(kind, List.of(args));
    }

    @Override
    public void handle(INetworkContext context) {
        DiskRequestDispatcher.handle(this, context);
    }
}
