package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.util.INebPayloadAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 两件事：
 * <ol>
 *   <li>暴露内部 buf。原版 {@code getData()} 是 {@code new FriendlyByteBuf(this.data.copy())}，
 *       Forge 的 {@code getInternalData()} 名不副实地也转调了它——在聚合热路径上，
 *       每个子包都要白白拷一次内存。</li>
 *   <li>放宽 1MB 净荷上限。聚合包会把一 tick 内的所有下行包塞进一个 payload，
 *       区块批量下发时轻松破 1MB。</li>
 * </ol>
 *
 * @author USS_Shenzhou
 */
@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class ClientboundCustomPayloadPacketMixin implements INebPayloadAccess {

    @Shadow
    @Final
    private FriendlyByteBuf data;

    @Unique
    private int neb$bakedSize;

    @Override
    public FriendlyByteBuf neb$rawData() {
        return this.data;
    }

    @Override
    public int neb$getBakedSize() {
        return this.neb$bakedSize;
    }

    @Override
    public void neb$setBakedSize(int size) {
        this.neb$bakedSize = size;
    }

    /**
     * 同时命中两个构造器里的 {@code 1048576}（发送侧的 {@code writerIndex() > N} 与
     * 接收侧的 {@code i <= N}）。上限来自 config 的 {@code maxPacketSize}，
     * 它自身已被 clamp 在 2MB~64MB，而 4 字节帧长能撑到 256MB，不会越界。
     */
    @ModifyConstant(
            method = {
                    "<init>(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/network/FriendlyByteBuf;)V",
                    "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V"
            },
            constant = @Constant(intValue = 1048576)
    )
    private int neb$widenPayloadLimit(int original) {
        var cfg = NotEnoughBandwidthConfig.get();
        return cfg == null ? original : Math.max(original, cfg.getMaxPacketSize());
    }
}
