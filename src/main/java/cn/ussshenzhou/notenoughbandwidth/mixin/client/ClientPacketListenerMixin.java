package cn.ussshenzhou.notenoughbandwidth.mixin.client;

import cn.ussshenzhou.notenoughbandwidth.NebChannels;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationCodec;
import cn.ussshenzhou.notenoughbandwidth.index.NebHandshake;
import cn.ussshenzhou.notenoughbandwidth.index.PayloadIndexManager;
import cn.ussshenzhou.notenoughbandwidth.stat.StatPayloads;
import cn.ussshenzhou.notenoughbandwidth.util.INebPayloadAccess;
import cn.ussshenzhou.notenoughbandwidth.util.NebConnectionState;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端侧的 NEB channel 拦截，与 {@code ServerGamePacketListenerImplMixin} 对称。
 * <p>
 * 额外多一件事：断线时把索引表清掉。索引表是<b>每连接</b>语义（不同服务端装的 mod 不同，
 * 表内容自然不同），但为了省内存做成了静态单例，所以必须在断线时显式失效，
 * 否则下次连别的服务器会在拿到新表之前用旧表解错包。
 *
 * @author USS_Shenzhou
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void neb$interceptNebChannels(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        var id = packet.getIdentifier();
        if (NebChannels.AGGREGATION.equals(id)) {
            ci.cancel();
            AggregationCodec.handle(
                    ((INebPayloadAccess) packet).neb$rawData(),
                    this.connection,
                    (ClientPacketListener) (Object) this,
                    ConnectionProtocol.PLAY,
                    PacketFlow.CLIENTBOUND
            );
        } else if (NebChannels.INDEX.equals(id)) {
            ci.cancel();
            NebHandshake.onIndexTableReceived(this.connection, ((INebPayloadAccess) packet).neb$rawData());
        } else if (NebChannels.STAT_RESPOND.equals(id)) {
            ci.cancel();
            StatPayloads.readRespond(((INebPayloadAccess) packet).neb$rawData());
        }
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void neb$onDisconnect(Component reason, CallbackInfo ci) {
        NebConnectionState.disable(this.connection);
        PayloadIndexManager.reset();
    }
}
