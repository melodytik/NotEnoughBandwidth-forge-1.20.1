package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.util.NebConnectionState;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import com.mojang.logging.LogUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * 出站包的分流点：能聚合的进缓冲，不能聚合的原样放行。
 *
 * <h4>为什么拦 send 而不是拦 PacketEncoder</h4>
 * 聚合的价值在于「把 N 个包合成 1 个再压」，必须在包还是对象的时候截住。
 * 到了 {@code PacketEncoder} 已经是逐包序列化的字节流，只能做单包压缩，压缩率天差地别
 * ——zstd 对小块数据几乎压不动，攒到几 KB 才有意义。
 *
 * @author USS_Shenzhou
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Inject(
            method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void neb$takeOverSend(Packet<?> packet, @Nullable PacketSendListener sendListener, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        // 带发送回调的包依赖 ChannelFuture 的成败通知，聚合后这层语义就没了，一律放行
        if (sendListener != null) {
            return;
        }
        if (!AggregationManager.ready() || !NebConnectionState.canAggregate(self)) {
            return;
        }

        // BundlePacket 的 write() 是空实现，真正的内容在 subPackets 里，
        // 原版靠 pipeline 上的 PacketBundleUnpacker 拆开。直接聚合它等于发一个空包。
        if (packet instanceof BundlePacket<?> bundle) {
            ci.cancel();
            // 不补 delimiter：同一批子包本就在同一个聚合包内，接收侧会在同一次
            // handle 里连续 reschedule 到主线程，bundle 想要的「同 tick 原子处理」照样成立。
            for (Packet<?> sub : bundle.subPackets()) {
                neb$offer(self, sub);
            }
            return;
        }

        if (neb$mustSendNow(packet)) {
            // 关键：黑名单包绕过缓冲直接发，但必须先把已排队的包吐出去，
            // 否则「后来的 keep_alive 反而先到」，包序就乱了。
            AggregationManager.flushConnection(self);
            return;
        }

        // 健壮性：先尝试聚合入缓冲，只有成功才取消原版发送。
        // 若 takeOver 抛异常（理论上不应发生），退化为原版发送，绝不丢包、绝不重复发。
        try {
            AggregationManager.takeOver(packet, self);
            ci.cancel();
        } catch (Throwable t) {
            LogUtils.getLogger().warn("NEB: failed to aggregate packet {}, sending vanilla instead.", packet.getClass().getName(), t);
        }
    }

    @Unique
    private void neb$offer(Connection self, Packet<?> sub) {
        if (neb$mustSendNow(sub)) {
            AggregationManager.flushConnection(self);
            self.send(sub);
        } else {
            try {
                AggregationManager.takeOver(sub, self);
            } catch (Throwable t) {
                // 子包聚合失败：退化为原版发送，绝不丢包。
                LogUtils.getLogger().warn("NEB: failed to aggregate sub-packet {}, sending vanilla instead.", sub.getClass().getName(), t);
                self.send(sub);
            }
        }
    }

    @Unique
    private boolean neb$mustSendNow(Packet<?> packet) {
        return NotEnoughBandwidthConfig.skipType(PacketUtil.getSkipKey(packet));
    }

    /**
     * 连接断开时立刻回收缓冲和 zstd 上下文。
     * 两边都用了 weakKeys/WeakHashMap 做兜底，但 zstd 上下文持有堆外内存，
     * 等 GC 不如现在就放。
     */
    @Inject(method = "handleDisconnection", at = @At("HEAD"))
    private void neb$onDisconnect(CallbackInfo ci) {
        AggregationManager.remove((Connection) (Object) this);
    }
}
