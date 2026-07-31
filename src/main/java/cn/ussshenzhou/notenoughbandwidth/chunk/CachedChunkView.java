package cn.ussshenzhou.notenoughbandwidth.chunk;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 1.20.1 版延迟区块缓存（DCC）的每玩家缓存视图。
 *
 * <p>NeoForge 原版依赖 {@code ChunkTrackingView} / {@code ChunkTrackingView.Positioned} /
 * {@code ServerPlayer.get/setChunkTrackingView} 这几个 coremod 注入的 API，而 1.20.1 Forge 完全没有，
 * 所以这里不复用那个接口，改为自包含一个 {@link ChunkPos} 中心 + 一个 {@link Long2LongLinkedOpenHashMap}
 * 缓存（key=packed ChunkPos，value=上次可见时间戳）。</p>
 *
 * <p>1.20.1 没有 NeoForge 那个 0 参 {@code updateChunkTracking(ServerPlayer)}，DCC 的唯一切入点是
 * {@code ChunkMap.move(ServerPlayer)} 内部逐区块调用的 5 参 {@code updateChunkTracking(...)}。
 * 本类不负责拦截，只负责记录「哪些区块被保留在客户端（缓存）」以及超时/超距清理，
 * 真正的发包/卸包决策在 {@code ChunkMapMixin} 里。</p>
 *
 * <p>与 NeoForge 原版不同，1.20.1 这里<b>不放大</b>玩家的 viewDistance（放大反而会让缓冲环区块被原版
 * 误判为「应正常发送」）。缓冲环的边界是 {@code 真实视野距离 + dccDistance}：落在该环内且离开真实视野的区块
 * 会被保留在客户端并加 ticket；超出该环、超时或超出容量上限则卸载。</p>
 *
 * @author USS_Shenzhou (ported by chocolate)
 */
public class CachedChunkView {
    private static final long NO_CACHE = -1;

    /**
     * 每玩家一份缓存视图。用 {@link WeakHashMap} 让玩家登出后被 GC 时自动清理，
     * 不必显式监听登出事件。
     */
    private static final WeakHashMap<ServerPlayer, CachedChunkView> STORES = new WeakHashMap<>();

    public static CachedChunkView getOrCreate(ServerPlayer player) {
        return STORES.computeIfAbsent(player, p -> new CachedChunkView(p.chunkPosition()));
    }

    public static CachedChunkView get(ServerPlayer player) {
        return STORES.get(player);
    }

    /**
     * 缓存：key=packed ChunkPos，value=上次可见时间戳（毫秒）。
     * 用 {@link Long2LongLinkedOpenHashMap} 保留插入顺序，方便按「最旧优先」淘汰。
     */
    private final Long2LongLinkedOpenHashMap cache = new Long2LongLinkedOpenHashMap();

    private ChunkPos center;
    /**
     * 该玩家当前的真实视野距离（由 {@code ChunkMap.getPlayerViewDistance} 写入）。
     * 缓冲环 = realViewDistance + dccDistance。
     */
    private int realViewDistance = 0;

    private CachedChunkView(ChunkPos center) {
        this.center = center;
        cache.defaultReturnValue(NO_CACHE);
    }

    public boolean isCached(long packed) {
        return cache.containsKey(packed);
    }

    public void cache(long packed) {
        cache.put(packed, System.currentTimeMillis());
    }

    public long uncache(long packed) {
        return cache.remove(packed);
    }

    public void setCenter(ChunkPos center) {
        this.center = center;
    }

    public void setViewDistance(int viewDistance) {
        this.realViewDistance = viewDistance;
    }

    public interface Context {
        void stopChunkTracking(ChunkPos pos);
    }

    /**
     * 每 tick 调用的清理：把「当前真实视野内（不再需要缓存）」「离中心过远（超出缓冲环）」
     * 「超时」或「超出容量上限」的缓存区块真正卸载。
     * 真正的卸包（{@code ClientboundForgetLevelChunkPacket} + 服务端记账）由 {@code Context.stopChunkTracking} 完成，
     * 它在 {@code DccTicker} 里通过 {@code ServerPlayer.untrackChunk} 触发原版卸载路径。
     */
    public void tick(ServerPlayer player, Context context) {
        long now = System.currentTimeMillis();
        var cfg = ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class);
        int dccDistance = cfg.dccDistance;
        int dccSizeLimit = cfg.dccSizeLimit;
        int dccTimeout = cfg.dccTimeout;
        long timeoutMs = TimeUnit.SECONDS.toMillis(dccTimeout);
        int bufferRadius = realViewDistance + dccDistance;

        enumerate((pos, time) -> {
            ChunkPos chunkPos = new ChunkPos(pos);
            int dist = center.getChessboardDistance(chunkPos);

            // 1) 当前真实视野内的区块：原版会正常保持并发送，不需要留在缓存里。
            //    只从缓存表移除，绝不要去卸载——否则客户端会丢区块。
            if (dist <= realViewDistance) {
                return CacheConsumer.REMOVE;
            }

            // 2) 超出缓冲环 → 卸载
            if (dist > bufferRadius) {
                context.stopChunkTracking(chunkPos);
                return CacheConsumer.REMOVE;
            }

            // 3) 超时 → 卸载
            if (time <= now - timeoutMs) {
                context.stopChunkTracking(chunkPos);
                return CacheConsumer.REMOVE;
            }

            // 4) 超出容量上限 → 从最旧开始淘汰，直到回到上限
            if (cache.size() > dccSizeLimit) {
                context.stopChunkTracking(chunkPos);
                return CacheConsumer.REMOVE;
            }

            return CacheConsumer.CONTINUE;
        });
    }

    @FunctionalInterface
    private interface CacheConsumer {
        byte CONTINUE = 0, REMOVE = 1, STOP = 2;

        @SuppressWarnings("unused")
        byte accept(long pos, long time);
    }

    private void enumerate(CacheConsumer consumer) {
        ObjectIterator<Long2LongMap.Entry> iterator = Long2LongMaps.fastIterator(cache);
        while (iterator.hasNext()) {
            Long2LongMap.Entry entry = iterator.next();

            byte v = consumer.accept(entry.getLongKey(), entry.getLongValue());
            if ((v & CacheConsumer.REMOVE) != 0) {
                iterator.remove();
            }
            if ((v & CacheConsumer.STOP) != 0) {
                return;
            }
        }
    }
}
