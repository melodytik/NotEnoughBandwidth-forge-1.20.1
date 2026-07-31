package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.index.PayloadPrefixHelper;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.RunningOnDifferentThreadException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合包的线格式编解码。
 *
 * <pre>
 * ┌---┬-----┬------┬------┬-----...
 * │ B │ (S) │  e0  │  e1  │ ...
 * └---┴-----┴------┴------┴-----...
 *             └---- 被 zstd 压缩 ----┘
 *
 * B = boolean, 是否压缩
 * S = VarInt, 解压后大小（仅 B == true 时存在）
 * e = 子包，格式见 AggregatedEncodePacket#encodeInto
 * </pre>
 *
 * <h4>线程模型</h4>
 * {@link #handle} 在 <b>netty 网络线程</b>被调用（与原版 {@code Packet.handle} 一致）。
 * 解压和拆包都在网络线程完成，随后逐个调用子包的 {@code handle}，
 * 子包内部的 {@code PacketUtils.ensureRunningOnSameThread} 会自行把它 reschedule 到主线程，
 * 顺序由主线程任务队列保证——与原版逐包收取的行为完全一致。
 *
 * @author USS_Shenzhou
 */
public class AggregationCodec {

    /** 压缩阈值：太小的包压了反而更大 */
    private static final int COMPRESS_THRESHOLD = 32;

    private static final Map<PacketFlow, Integer> CUSTOM_PAYLOAD_ID = new EnumMap<>(PacketFlow.class);

    /**
     * 反查 CustomPayload 包在 PLAY 协议里的 int id。
     * 解码时靠它判断「这个子包要不要读 channel 前缀」。
     */
    private static int customPayloadId(PacketFlow flow) {
        return CUSTOM_PAYLOAD_ID.computeIfAbsent(flow, f -> {
            Class<?> target = f == PacketFlow.CLIENTBOUND
                    ? ClientboundCustomPayloadPacket.class
                    : ServerboundCustomPayloadPacket.class;
            for (var entry : ConnectionProtocol.PLAY.getPacketsByIds(f).int2ObjectEntrySet()) {
                if (entry.getValue() == target) {
                    return entry.getIntKey();
                }
            }
            LogUtils.getLogger().error("NEB: cannot resolve CustomPayload packet id for {}", f);
            return -1;
        });
    }

    // ----------------------------------------encode----------------------------------------

    /**
     * 必须是 direct：{@link ZstdHelper#compress} 走 {@code compressDirectByteBufferStream}，
     * 输入必须是 direct 的 nioBuffer。
     */
    public static FriendlyByteBuf newRawBuffer() {
        return new FriendlyByteBuf(ByteBufAllocator.DEFAULT.directBuffer());
    }

    /**
     * 把攒好的 raw 封成可发送的净荷。
     * <p>
     * 拆成 {@code newRawBuffer / encodeInto / seal} 三步而非一把梭，是为了让上层能
     * <b>按真实字节数</b>在编码过程中切分大包——包大小事先无从估算（原版包只有 write 过才知道多大）。
     *
     * @return 净荷（调用方负责 release）；raw 为空时返回 null
     */
    public static FriendlyByteBuf seal(FriendlyByteBuf raw, Connection connection) {
        int rawSize = raw.readableBytes();
        if (rawSize == 0) {
            return null;
        }
        // 刻意用 unpooled heap：这个 buf 交给 CustomPayloadPacket 后由 netty 拷走，
        // 而 CustomPayloadPacket 双参构造是 shouldRelease=false，没人会去 release 它。
        // 用池化 direct 就是稳定泄漏，交给 GC 反而是这里最安全的选择。
        var out = new FriendlyByteBuf(Unpooled.buffer(rawSize / 2 + 16));
        boolean compress = rawSize >= COMPRESS_THRESHOLD;
        // B
        out.writeBoolean(compress);
        if (compress) {
            // S
            out.writeVarInt(rawSize);
            ByteBuf compressed = null;
            boolean compressedOk = false;
            try {
                compressed = ZstdHelper.compress(connection, raw);
                logCompressRatio(rawSize, compressed.readableBytes());
                out.writeBytes(compressed);
                compressedOk = true;
            } catch (Throwable t) {
                // 压缩失败绝不影响发包：退化为不压缩直接把原始字节写出去。
                // 否则一旦 zstd 抛异常（原生库缺失 / 配置为空 / 任何意外），
                // 这一整批聚合包都会丢失，表现为「连上后卡死 / 超时」。
                LogUtils.getLogger().warn("NEB: zstd compression failed, sending uncompressed for this batch "
                        + "(bandwidth cost only, connection stays alive).", t);
            } finally {
                if (compressed != null) {
                    compressed.release();
                }
            }
            if (!compressedOk) {
                // 重建 out：不压缩时线是 B(false) + raw，容量按 rawSize 算。
                // 直接复用原 out 会因容量不足（原 out 只按 rawSize/2 分配）而越界。
                out.release();
                out = new FriendlyByteBuf(Unpooled.buffer(rawSize + 5));
                out.writeBoolean(false);
                out.writeBytes(raw);
            }
        } else {
            out.writeBytes(raw);
        }
        // 记「增量」而非全量：真正上线的字节由 PacketEncoderMixin 统一计入 outRaw + outBaked，
        // 这里只补上压缩省掉的那部分，两者相加才等于未启用 NEB 时的原始流量。
        try {
            SimpleStatManager.outRaw(rawSize - out.readableBytes());
        } catch (Throwable ignored) {
            // 统计失败绝不影响发包
        }
        return out;
    }

    private static void logCompressRatio(int rawSize, int compressedSize) {
        var cfg = ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class);
        if (cfg != null && cfg.debugLog) {
            LogUtils.getLogger().debug("Packet aggregated and compressed: {} bytes -> {} bytes ({}%)",
                    rawSize, compressedSize, String.format("%.2f", 100f * compressedSize / rawSize));
        }
    }

    // ----------------------------------------decode + dispatch----------------------------------------

    /**
     * 在网络线程解包并分发。{@code payload} 由调用方负责释放。
     */
    public static void handle(FriendlyByteBuf payload, Connection connection, PacketListener listener,
                              ConnectionProtocol protocol, PacketFlow flow) {
        FriendlyByteBuf raw = null;
        boolean ownRaw = false;
        int onWireSize = payload.readableBytes();
        try {
            // B
            boolean compressed = payload.readBoolean();
            if (compressed) {
                // S
                int size = payload.readVarInt();
                raw = new FriendlyByteBuf(ZstdHelper.decompress(connection, payload, size));
                ownRaw = true;
            } else {
                raw = payload;
            }
            // 同 seal：只记增量，线上字节已由 PacketDecoderMixin 计入
            SimpleStatManager.inRaw(raw.readableBytes() - onWireSize);

            int customId = customPayloadId(flow);
            while (raw.readableBytes() > 0) {
                if (!readAndDispatchOne(raw, listener, protocol, flow, customId)) {
                    // 流已经错位，继续读只会读出垃圾，直接放弃剩余部分
                    break;
                }
            }
        } catch (Throwable t) {
            // 抓 Throwable 而非 Exception：zstd 解压可能因原生库问题抛 Error，
            // 绝不能让解包异常击穿网络线程、打断整条连接。
            LogUtils.getLogger().error("NEB: failed to handle aggregation packet.", t);
        } finally {
            if (ownRaw && raw != null) {
                raw.release();
            }
        }
    }

    /**
     * @return false 表示流已不可信，应中止后续解析
     */
    private static boolean readAndDispatchOne(FriendlyByteBuf raw, PacketListener listener,
                                              ConnectionProtocol protocol, PacketFlow flow, int customId) {
        int id;
        try {
            id = raw.readVarInt();
        } catch (Exception e) {
            LogUtils.getLogger().error("NEB: corrupted aggregation stream.", e);
            return false;
        }

        if (id == customId) {
            var channel = PayloadPrefixHelper.read(raw);
            int size = raw.readVarInt();
            if (channel == null) {
                // 索引对不上（多半是双方索引表不同步），丢掉这一个子包但流位置仍是对的
                LogUtils.getLogger().error("NEB: unknown payload index, skipped {} bytes.", size);
                raw.skipBytes(size);
                return true;
            }
            // 堆缓冲：子包会被 reschedule 到主线程，此时 raw 早已释放，必须脱离引用计数
            byte[] arr = new byte[size];
            raw.readBytes(arr);
            var data = new FriendlyByteBuf(Unpooled.wrappedBuffer(arr));
            Packet<?> sub = flow == PacketFlow.CLIENTBOUND
                    ? new ClientboundCustomPayloadPacket(channel, data)
                    : new ServerboundCustomPayloadPacket(channel, data);
            dispatch(sub, listener);
            return true;
        }

        int size = raw.readVarInt();
        if (size < 0 || size > raw.readableBytes()) {
            LogUtils.getLogger().error("NEB: illegal sub-packet size {} (id {}).", size, id);
            return false;
        }
        // 原版包的解码是同步的深拷贝语义，用 slice 即可，省一次内存拷贝
        var slice = new FriendlyByteBuf(raw.readSlice(size));
        Packet<?> sub;
        try {
            sub = protocol.createPacket(flow, id, slice);
        } catch (Exception e) {
            LogUtils.getLogger().error("NEB: failed to decode sub-packet id {}.", id, e);
            return true;
        }
        if (sub == null) {
            LogUtils.getLogger().error("NEB: unknown sub-packet id {}, skipped.", id);
            return true;
        }
        if (slice.readableBytes() > 0) {
            LogUtils.getLogger().warn("NEB: sub-packet id {} did not read all {} bytes.", id, slice.readableBytes());
        }
        dispatch(sub, listener);
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void dispatch(Packet<?> sub, PacketListener listener) {
        try {
            ((Packet) sub).handle(listener);
        } catch (RunningOnDifferentThreadException ignored) {
            // 正常路径：子包已被 reschedule 到主线程
        } catch (Exception e) {
            LogUtils.getLogger().error("NEB: failed to handle sub-packet {}.", sub.getClass().getName(), e);
        }
    }
}
