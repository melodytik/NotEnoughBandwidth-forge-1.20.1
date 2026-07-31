package cn.ussshenzhou.notenoughbandwidth.chunk;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 每 tick 驱动 {@link CachedChunkView#tick} 清理过期 / 超距 / 超容的 DCC 缓存。
 *
 * <p>在 {@link cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidth} 构造器里注册到
 * {@code MinecraftForge.EVENT_BUS}，这样无论专用服务器还是集成（单人）服务器都能驱动。
 * 对每个在线玩家取出其 {@link CachedChunkView}，把真正卸包的动作委托给
 * {@link ServerPlayer#untrackChunk}（发 {@code ClientboundForgetLevelChunkPacket} + 客户端记账，
 * 与原版卸载路径完全一致）。</p>
 *
 * @author chocolate
 */
public class DccTicker {
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            CachedChunkView view = CachedChunkView.get(player);
            if (view == null) {
                continue;
            }
            view.tick(player, player::untrackChunk);
        }
    }
}
