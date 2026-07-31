package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NebChannels;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationCodec;
import cn.ussshenzhou.notenoughbandwidth.index.NebHandshake;
import cn.ussshenzhou.notenoughbandwidth.stat.StatPayloads;
import cn.ussshenzhou.notenoughbandwidth.util.INebPayloadAccess;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端侧的 NEB channel 拦截。
 *
 * <h4>线程</h4>
 * 注入点在 {@code handleCustomPayload} 的 HEAD，此时仍在 <b>netty 网络线程</b>
 * ——原版那句 {@code PacketUtils.ensureRunningOnSameThread} 在方法体更靠后的位置。
 * 这正是我们要的：解压和拆包在网络线程做完，每个子包再各自 reschedule 到主线程，
 * 与原版逐包收取的时序完全一致。
 *
 * <h4>为什么必须 cancel</h4>
 * 不 cancel 的话 Forge 的 {@code NetworkHooks.onCustomPayload} 会去 NetworkRegistry
 * 查这个 channel，查不到就一路走到原版的 if-else 链末尾被丢弃——功能上无害，
 * 但白白多跑一遍，而且 {@code ServerboundCustomPayloadPacket#handle} 随后会 release
 * 掉我们还在用的 buf。
 *
 * @author USS_Shenzhou
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    @Final
    public Connection connection;

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void neb$interceptNebChannels(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        var id = packet.getIdentifier();
        if (NebChannels.AGGREGATION.equals(id)) {
            ci.cancel();
            AggregationCodec.handle(
                    ((INebPayloadAccess) packet).neb$rawData(),
                    this.connection,
                    (ServerGamePacketListenerImpl) (Object) this,
                    ConnectionProtocol.PLAY,
                    PacketFlow.SERVERBOUND
            );
        } else if (NebChannels.INDEX.equals(id)) {
            ci.cancel();
            NebHandshake.onClientAck(this.connection);
        } else if (NebChannels.STAT_QUERY.equals(id)) {
            ci.cancel();
            // 统计里含服务端整体流量，属于运维信息，限制为 OP(2) 及以上
            if (this.player != null && this.player.hasPermissions(2)) {
                this.connection.send(StatPayloads.respond());
            }
        }
    }
}
