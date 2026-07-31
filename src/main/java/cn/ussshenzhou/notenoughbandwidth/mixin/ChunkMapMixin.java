package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.chunk.CachedChunkView;
import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DCC 在 1.20.1 的唯一切入点。
 *
 * <p>NeoForge 把「区块跟踪」从 {@code ChunkMap.move} 里抽成了一个 0 参的
 * {@code updateChunkTracking(ServerPlayer)}，NEB 直接 {@code @Overwrite} 它，并依赖 NeoForge 注入的
 * {@code getPlayerViewDistance}/{@code markChunkPendingToSend}/{@code dropChunk}/{@code ticketStorage} 等辅助方法。
 * 1.20.1 Forge 完全没有这些（原版 {@code updateChunkTracking} 把「发包 / 发遗忘包 + 客户端记账」全部内联，
 * 票据由 {@code DistanceManager} 而非 {@code ticketStorage} 管理，{@code viewDistance} 还是包级私有字段），
 * 所以这里<b>不整体 {@code @Overwrite}</b>，而是用 {@code @Inject(cancellable=true)} 在方法 HEAD 注入决策：</p>
 * <ul>
 *   <li>区块进入真实视野且命中缓存 → 取消发包（客户端早就有，跳过整包重发）；</li>
 *   <li>区块离开真实视野但仍在缓冲环（服务端视野 + dccDistance）内 → 取消原版卸载，客户端保留区块，记入缓存；</li>
 *   <li>其余（正常加载 / 正常卸载）→ 原样放行，由原版处理。</li>
 * </ul>
 *
 * <p><b>健壮性约束（关键）：</b>本方法运行在「真实玩家连入后逐区块跟踪」的热路径上。任何异常都<b>绝不可以</b>
 * 向外抛出——一旦抛出，调用方（{@code ChunkMap} 的区块跟踪循环）会中断，轻则玩家区块状态错乱，
 * 重则网络线程崩溃导致连接断开。因此：
 * <ul>
 *   <li>全程包在 try/catch 里；catch 到异常时<b>不 cancel</b>（即退化为原版行为），保证区块永远能按原版路径正常收发；</li>
 *   <li>所有可能为 null 的访问（{@code player.getServer()}、配置等）都做保护；</li>
 *   <li>只有两条判定分支内部、且状态完全确定时，才 {@code ci.cancel()}。</li>
 * </ul>
 *
 * @author chocolate
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
    private void nebUpdateChunkTracking(ServerPlayer player, ChunkPos pos, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, boolean wasLoaded, boolean load, CallbackInfo ci) {
        try {
            if (player == null || pos == null) {
                return;
            }
            var server = player.getServer();
            if (server == null) {
                return;
            }
            var cfg = NotEnoughBandwidthConfig.get();
            if (cfg == null || !cfg.dccEnabled) {
                return;
            }

            CachedChunkView view = CachedChunkView.getOrCreate(player);
            var center = player.chunkPosition();
            if (center == null) {
                return;
            }
            view.setCenter(center);

            int serverViewDistance = server.getPlayerList().getViewDistance();
            view.setViewDistance(serverViewDistance);

            int dccDistance = cfg.dccDistance;
            int bufferRadius = serverViewDistance + dccDistance;
            long packed = pos.toLong();
            int dist = center.getChessboardDistance(pos);

            if (!load && wasLoaded) {
                // 区块离开真实视野
                if (dist <= bufferRadius) {
                    // 落在缓冲环内：抑制原版 unload（客户端保留区块），记入缓存，留待 ticker 到期再卸包。
                    // 这一步是安全的：被抑制的只是「发给客户端的遗忘包」，服务端世界里的区块本来就还在。
                    view.cache(packed);
                    ci.cancel();
                } else {
                    // 超出缓冲环：正常卸载（原版 untrackChunk 会发遗忘包 + 客户端记账）
                    view.uncache(packed);
                }
            } else if (load && !wasLoaded) {
                // 区块进入真实视野：若客户端早就有（缓存命中），跳过发包。
                // 仅当确实命中缓存才取消——缓存由本 mixin 在「离开视野且落在缓冲环」时写入，
                // 只要 ticker 没把它误删，命中即代表客户端确实持有该区块，取消发包是安全的。
                if (view.isCached(packed)) {
                    view.uncache(packed);
                    ci.cancel();
                }
            }
            // load&&wasLoaded / !load&&!wasLoaded：原样放行（vanilla 内部无变化的重复调用）
        } catch (Throwable t) {
            // 任何意外都退化为原版行为：不取消、不缓存，让原版正常发包/卸包。
            LogUtils.getLogger().warn("NEB: DCC updateChunkTracking threw, falling back to vanilla for this chunk.", t);
        }
    }
}
