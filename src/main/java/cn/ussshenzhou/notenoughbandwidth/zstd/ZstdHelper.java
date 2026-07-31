package cn.ussshenzhou.notenoughbandwidth.zstd;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import com.google.common.cache.Cache;
import com.mojang.logging.LogUtils;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.concurrent.ExecutionException;

/**
 * @author USS_Shenzhou
 */
public class ZstdHelper {

    private static final Cache<Connection, Context> ZSTD_CONTEXT_CACHE = CacheBuilder.newBuilder()
            .weakKeys()
            .removalListener((RemovalListener<Connection, Context>) notification -> {
                if (notification.getValue() != null) {
                    notification.getValue().close();
                }
            })
            .build();
    private static final Cache<Connection, Boolean> CONNECTION_USE_CONTEXT = CacheBuilder.newBuilder()
            .weakKeys()
            .build();

    public static ByteBuf compress(Connection connection, ByteBuf raw) {
        return Unpooled.wrappedBuffer(get(connection).compress(raw.nioBuffer()));
    }

    public static ByteBuf decompress(Connection connection, ByteBuf compressed, int originalSize) {
        if (compressed.isDirect()) {
            return Unpooled.wrappedBuffer(get(connection).decompress(compressed.nioBuffer(), originalSize));
        } else {
            var directBuf = Unpooled.directBuffer(compressed.readableBytes());
            compressed.getBytes(compressed.readerIndex(), directBuf);
            var decompressed = Unpooled.wrappedBuffer(get(connection).decompress(directBuf.nioBuffer(), originalSize));
            directBuf.release();
            return decompressed;
        }
    }

    private static Context get(Connection connection) {
        ZSTD_CONTEXT_CACHE.asMap().entrySet().removeIf(e -> !e.getKey().isConnected());
        try {
            return ZSTD_CONTEXT_CACHE.get(connection, () -> new Context(useContext(connection)));
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean useContext(Connection connection) {
        try {
            if (connection.getReceiving() == PacketFlow.CLIENTBOUND) {
                return true;
            }
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return true;
            }
            Boolean result = CONNECTION_USE_CONTEXT.getIfPresent(connection);
            if (result != null) {
                return result;
            }
            var players = server.getPlayerList().getPlayers();
            var to = players.stream()
                    // 1.20.1: ServerGamePacketListenerImpl exposes the Connection as a public field
                    .filter(p -> p != null && p.connection != null && p.connection.connection != null
                            && p.connection.connection.equals(connection))
                    .findFirst()
                    .orElse(null);
            if (to == null) {
                CONNECTION_USE_CONTEXT.put(connection, true);
                return true;
            }
            var cfg = NotEnoughBandwidthConfig.get();
            var use = cfg != null && cfg.playersDoNotUseContext.contains(to.getUUID().toString());
            CONNECTION_USE_CONTEXT.put(connection, use);
            return use;
        } catch (Throwable t) {
            // 任何意外（配置未就绪、玩家列表为空等）都不该阻断发包，退化为「使用上下文」
            LogUtils.getLogger().warn("NEB: failed to decide context mode, defaulting to true.", t);
            return true;
        }
    }
}
