package cn.ussshenzhou.notenoughbandwidth.util;

import io.netty.channel.local.LocalAddress;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;

/**
 * 每条连接的 NEB 启用状态，挂在 netty channel 的 attr 上。
 * <p>
 * 聚合必须双方都就绪才能开：服务端要先把索引表发下去，客户端要先收到并装好索引表。
 * 在此之前一律走原版路径，保证任何情况下都不会发出对端读不懂的数据。
 *
 * @author USS_Shenzhou
 */
public class NebConnectionState {

    private static final AttributeKey<Boolean> ENABLED = AttributeKey.valueOf("neb_aggregation_enabled");

    public static void enable(Connection connection) {
        var channel = connection.channel();
        if (channel != null) {
            channel.attr(ENABLED).set(Boolean.TRUE);
        }
    }

    public static void disable(Connection connection) {
        var channel = connection.channel();
        if (channel != null) {
            channel.attr(ENABLED).set(Boolean.FALSE);
        }
    }

    /**
     * 聚合是否可用：已就绪 + 处于 PLAY 阶段 + 不是单人游戏的本地内存连接。
     */
    public static boolean canAggregate(Connection connection) {
        var channel = connection.channel();
        if (channel == null || !channel.isOpen()) {
            return false;
        }
        // 单人游戏走 LocalChannel，根本不过网卡，压缩纯属浪费 CPU
        if (connection.getRemoteAddress() instanceof LocalAddress) {
            return false;
        }
        if (!Boolean.TRUE.equals(channel.attr(ENABLED).get())) {
            return false;
        }
        return channel.attr(Connection.ATTRIBUTE_PROTOCOL).get() == ConnectionProtocol.PLAY;
    }
}
