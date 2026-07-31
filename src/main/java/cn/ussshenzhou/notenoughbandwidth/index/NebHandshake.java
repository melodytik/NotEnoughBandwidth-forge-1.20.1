package cn.ussshenzhou.notenoughbandwidth.index;

import cn.ussshenzhou.notenoughbandwidth.NebChannels;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.util.NebConnectionState;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.local.LocalAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;

/**
 * NEB 的能力协商。
 *
 * <pre>
 * server                                   client
 *   │  ── neb:i  [索引表] ────────────────▶  │  装表
 *   │                                        │  开启聚合（下行已可解）
 *   │  ◀───────────────── neb:i  [空] ────   │  ACK
 *   │  开启聚合（上行已可解）                  │
 * </pre>
 *
 * <h4>为什么必须握手</h4>
 * 聚合包对没装 NEB 的对端就是一串读不懂的字节。原版客户端收到未知 channel 的
 * CustomPayload 只会静默丢弃——不会崩，但整局游戏的包全丢了，表现为「连上就卡死」。
 * 所以服务端在收到 ACK 之前绝不聚合，原版客户端因此自动退化到完全原版的行为。
 * 反过来，客户端也只有拿到索引表才开始聚合上行。
 *
 * @author USS_Shenzhou
 */
public class NebHandshake {

    /** 服务端：玩家进入 PLAY 后下发索引表 */
    public static void sendIndexTable(Connection connection) {
        if (connection.getRemoteAddress() instanceof LocalAddress) {
            // 单人 / 本地存档走 LocalChannel，数据根本不过网卡，压缩纯属烧 CPU
            return;
        }
        try {
            if (!PayloadIndexManager.ready()) {
                PayloadIndexManager.initFromLocal();
            }
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            PayloadIndexManager.writeTable(buf);
            connection.send(new ClientboundCustomPayloadPacket(NebChannels.INDEX, buf));
        } catch (Exception e) {
            LogUtils.getLogger().error("NEB: failed to send payload index table.", e);
        }
    }

    /** 客户端：收到索引表，装好后开启聚合并回 ACK */
    public static void onIndexTableReceived(Connection connection, FriendlyByteBuf data) {
        try {
            PayloadIndexManager.initFromBuffer(data);
            AggregationManager.init();
            NebConnectionState.enable(connection);
            // 空净荷即 ACK。走裸 CustomPayload 而非 SimpleChannel，
            // 因为此时服务端那边的 mixin 是按 channel id 直接拦的。
            connection.send(new ServerboundCustomPayloadPacket(
                    NebChannels.INDEX, new FriendlyByteBuf(Unpooled.buffer(0))));
            LogUtils.getLogger().info("NEB: enabled, payload index table installed.");
        } catch (Exception e) {
            LogUtils.getLogger().error("NEB: failed to install payload index table, staying vanilla.", e);
            NebConnectionState.disable(connection);
        }
    }

    /** 服务端：收到客户端 ACK，此后上下行都可聚合 */
    public static void onClientAck(Connection connection) {
        AggregationManager.init();
        NebConnectionState.enable(connection);
        LogUtils.getLogger().debug("NEB: client acknowledged, aggregation enabled for {}.",
                connection.getRemoteAddress());
    }
}
