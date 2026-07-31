package cn.ussshenzhou.notenoughbandwidth.stat;

import cn.ussshenzhou.notenoughbandwidth.NebChannels;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;

/**
 * {@code neb:sq} / {@code neb:sr} 两个统计 channel 的编解码。
 * <p>
 * 1.20.1 没有 {@code CustomPacketPayload}/{@code StreamCodec}，原版 NEB 里那两个
 * payload 类在这里退化成一对静态方法 —— 直接构造原版 CustomPayload 包，
 * 由 {@code ServerGamePacketListenerImplMixin} / {@code ClientPacketListenerMixin} 拦截处理。
 * <p>
 * 刻意<b>不走 Forge SimpleChannel</b>：与 {@link NebChannels} 其余 channel 保持一致，
 * 避免多套一层 {@code fml:play} + discriminator。
 *
 * @author USS_Shenzhou
 */
public final class StatPayloads {

    private StatPayloads() {
    }

    /**
     * 客户端 → 服务端的查询包。空 body，channel id 本身就是全部信息。
     */
    public static ServerboundCustomPayloadPacket query() {
        return new ServerboundCustomPayloadPacket(NebChannels.STAT_QUERY, new FriendlyByteBuf(Unpooled.buffer(0)));
    }

    /**
     * 服务端 → 客户端的统计快照。
     * <p>
     * 计数用 VarLong（绝大多数时候远小于 8 字节），速率用 double 保精度。
     */
    public static ClientboundCustomPayloadPacket respond() {
        var d = SimpleStatManager.LOCAL;
        // 4 个 VarLong 最多 40 字节 + 4 个 double 32 字节
        var buf = new FriendlyByteBuf(Unpooled.buffer(72));
        buf.writeVarLong(d.inboundBytesBaked().get());
        buf.writeVarLong(d.inboundBytesRaw().get());
        buf.writeVarLong(d.outboundBytesBaked().get());
        buf.writeVarLong(d.outboundBytesRaw().get());
        buf.writeDouble(d.inboundSpeedBaked().averageIn1s());
        buf.writeDouble(d.inboundSpeedRaw().averageIn1s());
        buf.writeDouble(d.outboundSpeedBaked().averageIn1s());
        buf.writeDouble(d.outboundSpeedRaw().averageIn1s());
        return new ClientboundCustomPayloadPacket(NebChannels.STAT_RESPOND, buf);
    }

    /**
     * 客户端侧解包，写进 {@link SimpleStatManager} 的 server 镜像字段。
     * <p>
     * 调用点在 netty 线程，但这些字段只被 StatScreen 读取用于显示，
     * 精确性要求为零，因此不加锁也不用 volatile。
     */
    public static void readRespond(FriendlyByteBuf buf) {
        SimpleStatManager.inboundBytesBakedServer = buf.readVarLong();
        SimpleStatManager.inboundBytesRawServer = buf.readVarLong();
        SimpleStatManager.outboundBytesBakedServer = buf.readVarLong();
        SimpleStatManager.outboundBytesRawServer = buf.readVarLong();
        SimpleStatManager.inboundSpeedBakedServer = buf.readDouble();
        SimpleStatManager.inboundSpeedRawServer = buf.readDouble();
        SimpleStatManager.outboundSpeedBakedServer = buf.readDouble();
        SimpleStatManager.outboundSpeedRawServer = buf.readDouble();
    }
}
