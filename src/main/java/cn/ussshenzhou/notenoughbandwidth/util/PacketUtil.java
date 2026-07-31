package cn.ussshenzhou.notenoughbandwidth.util;

import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 1.20.1 没有 {@code PacketType} / {@code CustomPacketPayload}，原版包在 ConnectionProtocol 里以 int id 索引，
 * 只有 CustomPayload 包才带 ResourceLocation。因此这里区分两种「标识」：
 * <ul>
 *     <li>{@link #getPayloadId(Packet)} —— 仅 CustomPayload 包有值，用于索引压缩</li>
 *     <li>{@link #getSkipKey(Packet)} —— 用于黑名单匹配，原版关键包映射到 {@code minecraft:xxx} 伪 id</li>
 * </ul>
 *
 * @author USS_Shenzhou
 */
public class PacketUtil {

    private static final Map<Class<?>, String> VANILLA_KEYS = new HashMap<>();

    static {
        // 这些包要么涉及协议状态切换，要么涉及时延测量，绝不能被缓冲聚合
        VANILLA_KEYS.put(ClientboundLoginPacket.class, "minecraft:login");
        VANILLA_KEYS.put(ClientboundDisconnectPacket.class, "minecraft:disconnect");
        VANILLA_KEYS.put(ClientboundKeepAlivePacket.class, "minecraft:keep_alive");
        VANILLA_KEYS.put(ServerboundKeepAlivePacket.class, "minecraft:keep_alive");
        VANILLA_KEYS.put(ClientboundPingPacket.class, "minecraft:ping");
        VANILLA_KEYS.put(ServerboundPongPacket.class, "minecraft:pong");
        VANILLA_KEYS.put(ClientboundRespawnPacket.class, "minecraft:respawn");
        // 1.20.1 只有泛型基类 BundleDelimiterPacket<T>，没有 Clientbound/Serverbound 子类，
        // 所以实例的 getClass() 恒等于它本身
        VANILLA_KEYS.put(BundleDelimiterPacket.class, "minecraft:bundle_delimiter");
    }

    /**
     * @return CustomPayload 包的 channel id；非 CustomPayload 返回 null
     */
    @Nullable
    public static ResourceLocation getPayloadId(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket p) {
            return p.getIdentifier();
        } else if (packet instanceof ServerboundCustomPayloadPacket p) {
            return p.getIdentifier();
        }
        return null;
    }

    /**
     * @return 用于 {@code NotEnoughBandwidthConfig.skipType} 匹配的 key，无对应 key 时返回空串
     */
    public static String getSkipKey(Packet<?> packet) {
        var payloadId = getPayloadId(packet);
        if (payloadId != null) {
            return payloadId.toString();
        }
        return VANILLA_KEYS.getOrDefault(packet.getClass(), "");
    }
}
