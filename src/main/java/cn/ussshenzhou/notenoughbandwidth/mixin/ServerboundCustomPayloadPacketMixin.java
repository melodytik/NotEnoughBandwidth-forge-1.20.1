package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.util.INebPayloadAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 上行同理。原版上限只有 32767 —— 上行流量本来就小，但聚合包一旦攒满一 tick 的
 * 移动/交互包也可能擦线，这里同样按 config 放宽。
 * <p>
 * 注意上行的 {@code getData()} 返回的就是内部 buf（不像下行会 copy），
 * 但仍然实现 {@link INebPayloadAccess} 以便调用侧不必区分方向。
 *
 * @author USS_Shenzhou
 */
@Mixin(ServerboundCustomPayloadPacket.class)
public abstract class ServerboundCustomPayloadPacketMixin implements INebPayloadAccess {

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

    @ModifyConstant(
            method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            constant = @Constant(intValue = 32767)
    )
    private int neb$widenPayloadLimit(int original) {
        var cfg = NotEnoughBandwidthConfig.get();
        return cfg == null ? original : Math.max(original, cfg.getMaxPacketSize());
    }
}
