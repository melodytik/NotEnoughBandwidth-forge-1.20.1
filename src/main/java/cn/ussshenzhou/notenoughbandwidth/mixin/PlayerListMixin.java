package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.index.NebHandshake;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 索引表的下发时机。
 * <p>
 * 1.20.1 没有 1.20.2+ 的 CONFIGURATION 阶段，能力协商只能挤进 PLAY。
 * 选 {@code placeNewPlayer} 的 TAIL 而不是 {@code ServerGamePacketListenerImpl} 的构造器，
 * 是因为构造器执行时玩家的世界/装备/权限都还没同步完，此刻插一个自定义包进去
 * 容易和其它 mod 的登录逻辑抢顺序；TAIL 时协议已稳定在 PLAY，一切初始化都已完成。
 *
 * @author USS_Shenzhou
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void neb$sendIndexTable(Connection netManager, ServerPlayer player, CallbackInfo ci) {
        NebHandshake.sendIndexTable(netManager);
    }
}
