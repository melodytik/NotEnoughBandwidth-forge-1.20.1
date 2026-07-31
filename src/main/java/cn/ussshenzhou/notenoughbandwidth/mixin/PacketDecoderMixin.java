package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 入站流量计量点，口径与 {@link PacketEncoderMixin} 完全对称。
 * <p>
 * 此处 {@code in} 已经被 {@code Varint21FrameDecoder} 切成单帧，
 * {@code readableBytes()} 就是这个包在线路上的净字节数（不含长度前缀）。
 *
 * @author USS_Shenzhou
 */
@Mixin(PacketDecoder.class)
public class PacketDecoderMixin {

    @Inject(
            method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",
            at = @At("HEAD")
    )
    private void neb$statIn(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        int size = in.readableBytes();
        SimpleStatManager.inBaked(size);
        SimpleStatManager.inRaw(size);
    }
}
