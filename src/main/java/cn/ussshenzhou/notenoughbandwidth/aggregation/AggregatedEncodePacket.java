package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.index.PayloadPrefixHelper;
import cn.ussshenzhou.notenoughbandwidth.util.INebPayloadAccess;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 待聚合的单个子包。
 *
 * <h4>与 NeoForge 原版实现的差异</h4>
 * 26.x 走 {@code IdDispatchCodec} / {@code NetworkRegistry.getCodec} 拿 StreamCodec 来编码；
 * 1.20.1 没有 codec 体系，原版包直接 {@link Packet#write(FriendlyByteBuf)}，
 * CustomPayload 则手动拆成「紧凑 channel 前缀 + 净荷」，跳过原版那个昂贵的 {@code writeResourceLocation}。
 *
 * @author USS_Shenzhou
 */
public class AggregatedEncodePacket {

    public final Packet<?> packet;
    /** 非 null 说明这是个 CustomPayload 包 */
    @Nullable
    public final ResourceLocation payloadId;

    public AggregatedEncodePacket(Packet<?> packet) {
        this.packet = packet;
        this.payloadId = PacketUtil.getPayloadId(packet);
    }

    /**
     * <pre>
     * ┌---------------┬--------------------┬-----------┬--------┐
     * │ vanillaId(VI) │ [channel 前缀]      │  size(VI) │  data  │
     * └---------------┴--------------------┴-----------┴--------┘
     *                  仅 CustomPayload 才有
     * </pre>
     * 编码失败时回滚 writerIndex，保证不会把半个包写进流里污染后续所有子包。
     */
    public void encodeInto(FriendlyByteBuf raw, ConnectionProtocol protocol, PacketFlow flow) {
        int id = protocol.getPacketId(flow, packet);
        if (id < 0) {
            LogUtils.getLogger().error("Skipped: sending unregistered packet {}", packet.getClass().getName());
            return;
        }
        int mark = raw.writerIndex();
        try {
            raw.writeVarInt(id);
            if (payloadId != null) {
                PayloadPrefixHelper.write(payloadId, raw);
                var data = ((INebPayloadAccess) packet).neb$rawData();
                int len = data.readableBytes();
                raw.writeVarInt(len);
                // 用带 index 的重载，不动 data 自身的 readerIndex（包可能被复用发送）
                raw.writeBytes(data, data.readerIndex(), len);
            } else {
                var d = new FriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer());
                try {
                    packet.write(d);
                    raw.writeVarInt(d.readableBytes());
                    raw.writeBytes(d);
                } finally {
                    d.release();
                }
            }
        } catch (Exception e) {
            raw.writerIndex(mark);
            LogUtils.getLogger().error("Skipped: failed to encode packet {}", packet.getClass().getName(), e);
        }
    }
}
