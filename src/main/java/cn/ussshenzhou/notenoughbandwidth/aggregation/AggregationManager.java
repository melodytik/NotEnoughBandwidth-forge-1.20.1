package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.NebChannels;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 逐连接的发送缓冲，每 20ms（一 tick）打包一次。
 *
 * @author USS_Shenzhou
 */
public class AggregationManager {

    private static final Map<Connection, ArrayList<AggregatedEncodePacket>> PACKET_BUFFER =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("NEB-Flush-thread").setDaemon(true).build());
    private static final ArrayList<ScheduledFuture<?>> TASKS = new ArrayList<>();
    private static volatile boolean initialized = false;

    public synchronized static void init() {
        if (initialized) {
            return;
        }
        PACKET_BUFFER.clear();
        TASKS.forEach(task -> task.cancel(false));
        TASKS.clear();
        TASKS.add(TIMER.scheduleAtFixedRate(AggregationManager::flushAll, 0,
                AggregationFlushHelper.getFlushPeriodInMilliseconds(), TimeUnit.MILLISECONDS));
        initialized = true;
    }

    public static boolean ready() {
        return initialized;
    }

    public static void takeOver(Packet<?> packet, Connection connection) {
        synchronized (PACKET_BUFFER) {
            PACKET_BUFFER.computeIfAbsent(connection, c -> new ArrayList<>())
                    .add(new AggregatedEncodePacket(packet));
        }
    }

    private static void flushAll() {
        ArrayList<Connection> connections;
        synchronized (PACKET_BUFFER) {
            PACKET_BUFFER.entrySet().removeIf(e -> !e.getKey().isConnected());
            connections = new ArrayList<>(PACKET_BUFFER.keySet());
        }
        for (var connection : connections) {
            flushConnection(connection);
        }
    }

    private static final Map<Connection, Object> LOCKS = Collections.synchronizedMap(new WeakHashMap<>());

    private static Object lockOf(Connection connection) {
        return LOCKS.computeIfAbsent(connection, c -> new Object());
    }

    /**
     * <b>同步</b>刷出指定连接的缓冲。
     * <p>
     * 两点刻意为之：
     * <ol>
     *   <li>不做成异步——黑名单包（keep_alive / disconnect 等）绕过聚合直接发送前必须先把缓冲清空，
     *       否则先入队的包反而后到，包序就乱了；异步 flush 给不出这个保证。</li>
     *   <li>整段串行化——zstd 走的是<b>带上下文的流式压缩</b>，压缩顺序必须严格等于解压顺序。
     *       「换出缓冲 → 编码 → 压缩 → 发送」如果被两个线程交错执行，压缩流就会永久损坏。
     *       所以锁的范围必须覆盖到 send，而不能只锁 buffer。</li>
     * </ol>
     */
    public static void flushConnection(Connection connection) {
        synchronized (lockOf(connection)) {
            ArrayList<AggregatedEncodePacket> packets;
            synchronized (PACKET_BUFFER) {
                packets = PACKET_BUFFER.get(connection);
                if (packets == null || packets.isEmpty()) {
                    return;
                }
                // 换出而非清空：编码期间主线程可能继续投递新包
                PACKET_BUFFER.put(connection, new ArrayList<>());
            }
            flushInternal(connection, packets);
        }
    }

    private static void flushInternal(Connection connection, ArrayList<AggregatedEncodePacket> packets) {
        var protocol = protocolOf(connection);
        if (protocol == null) {
            return;
        }
        var flow = connection.getSending();
        int threshold = AggregationFlushHelper.getEarlyFlushThreshold();

        FriendlyByteBuf raw = AggregationCodec.newRawBuffer();
        try {
            for (var p : packets) {
                p.encodeInto(raw, protocol, flow);
                // 边编码边看真实字节数，超阈值就先发一批。
                // 包大小事先估不准（原版包只有 write 过才知道多大），这是唯一可靠的切分点。
                if (raw.readableBytes() >= threshold) {
                    sendSealed(connection, raw, flow);
                    raw.release();
                    raw = AggregationCodec.newRawBuffer();
                }
            }
            if (raw.readableBytes() > 0) {
                sendSealed(connection, raw, flow);
            }
        } catch (Throwable t) {
            // 抓 Throwable：zstd 原生库问题可能抛 Error，绝不能让刷包线程死掉，
            // 否则缓冲里的包永远发不出去，客户端表现为「连上后卡死 / 超时」。
            LogUtils.getLogger().error("Skipped: failed to flush packets.", t);
        } finally {
            raw.release();
        }
    }

    private static void sendSealed(Connection connection, FriendlyByteBuf raw, PacketFlow flow) {
        var payload = AggregationCodec.seal(raw, connection);
        if (payload == null) {
            return;
        }
        // payload 是 unpooled heap buf（见 AggregationCodec#seal），CustomPayloadPacket 不会替我们释放，
        // 也不需要——GC 会收。这里不能主动 release，因为 write() 时才真正把字节拷进 netty 输出缓冲。
        Packet<?> packet = flow == PacketFlow.CLIENTBOUND
                ? new ClientboundCustomPayloadPacket(NebChannels.AGGREGATION, payload)
                : new ServerboundCustomPayloadPacket(NebChannels.AGGREGATION, payload);
        connection.send(packet);
    }

    @Nullable
    private static ConnectionProtocol protocolOf(Connection connection) {
        var channel = connection.channel();
        if (channel == null) {
            return null;
        }
        return channel.attr(Connection.ATTRIBUTE_PROTOCOL).get();
    }

    public static void remove(Connection connection) {
        PACKET_BUFFER.remove(connection);
    }
}
