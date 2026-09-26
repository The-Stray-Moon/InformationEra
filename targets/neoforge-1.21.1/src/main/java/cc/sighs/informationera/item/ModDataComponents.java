package cc.sighs.informationera.item;

import cc.sighs.informationera.InformationEraNeoForge121;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 本模组的物品组件。 */
public final class ModDataComponents {

    private ModDataComponents() {
    }

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, InformationEraNeoForge121.MOD_ID);

    /**
     * 储存卡绑定的磁盘 uuid，存字符串形式。
     *
     * <p>用字符串而不是 UUID 类型：持久化与网络同步都直接复用现成的 STRING 编解码器，
     * 少一处自定义 codec 就少一处出错的地方。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DISK_UUID =
            COMPONENTS.register("disk_uuid", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    /**
     * 卡上记的磁盘名，只是给 tooltip 看的副本。
     *
     * <p>名字的真身在服务端的磁盘数据里、跟 uuid 走；卡上这份由服务端每次处理请求时刷新，
     * 所以改完盘名之后拿起卡就能看到新名字，而不是停留在初次绑定时的那个。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DISK_NAME =
            COMPONENTS.register("disk_name", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
