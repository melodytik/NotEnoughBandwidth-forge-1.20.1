package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 出站流量的唯一计量点。包大小上限交由原版与 packetfixer 等同类 mod 处理，
 *
 * <h4>统计口径</h4>
 * 这里记的是<b>真正写进 socket 的字节</b>（baked），并且同一个值也记进 raw。
 * 聚合压缩省下的部分由 {@code AggregationCodec#seal} 以<b>增量</b>形式补记，
 * 于是：
 * <pre>
 * raw   = 未启用 NEB 时本该发出的字节数
 * baked = 实际发出的字节数
 * </pre>
 * 两者相除就是端到端压缩率。这样拆分的好处是无需在任何地方判断「这个包是不是聚合包」。
 *
 * @author USS_Shenzhou
 */
@Mixin(PacketEncoder.class)
public class PacketEncoderMixin {

    @Inject(
            method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("TAIL")
    )
    private void neb$statOut(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        // MessageToByteEncoder 每次调用都会新分配 out，readableBytes 就是本包大小
        int size = out.readableBytes();
        SimpleStatManager.outBaked(size);
        SimpleStatManager.outRaw(size);
    }

}
