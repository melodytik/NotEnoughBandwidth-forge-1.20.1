package cn.ussshenzhou.notenoughbandwidth.index;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeSet;

/**
 * CustomPayload channel 的一级索引表。
 *
 * <h4>与 NeoForge 原版实现的差异</h4>
 * NeoForge 26.x 里所有包（含原版包）都以 {@code Identifier} 为类型标识，所以原实现做了 namespace/path 两级索引。
 * 1.20.1 里原版包在 {@link net.minecraft.network.ConnectionProtocol} 中本就是 int id，
 * 只有 CustomPayload 包携带 ResourceLocation，因此这里退化为**一级索引**：
 * 一个 VarInt 即可定位，比两级索引更省字节，也不需要「试读 Identifier 猜是否被索引」的 hack。
 *
 * <h4>索引一致性</h4>
 * 索引表由**服务端**在玩家进入 PLAY 阶段时随 {@code neb:i} 下发，客户端照单全收。
 * 双方共用同一张表，因此上下行都可复用。索引 0 保留，表示「未索引」，此时线上紧跟完整 ResourceLocation。
 *
 * @author USS_Shenzhou
 */
public class PayloadIndexManager {

    /** 索引 0 保留给「未索引」，故列表 0 号位是占位符 */
    private static final ResourceLocation PLACEHOLDER = new ResourceLocation("neb", "unindexed");

    private static volatile boolean initialized = false;
    private static final ArrayList<ResourceLocation> BY_INDEX = new ArrayList<>();
    private static final Object2IntOpenHashMap<ResourceLocation> TO_INDEX = new Object2IntOpenHashMap<>();

    /** 表最大容量，超出则拒绝索引（VarInt 3 字节以内足够覆盖，实际远用不到） */
    private static final int MAX_ENTRIES = 65536;

    static {
        TO_INDEX.defaultReturnValue(0);
    }

    // ----------------------------------------服务端：本地构建----------------------------------------

    /**
     * 反射 Forge {@code NetworkRegistry.instances} 收集所有已注册的 channel。
     * 拿不到就退化成空表——此时所有 payload 都走完整 ResourceLocation，功能不受影响，只是省得少。
     */
    public synchronized static void initFromLocal() {
        var ids = new TreeSet<ResourceLocation>(Comparator.comparing(ResourceLocation::toString));
        try {
            Field f = net.minecraftforge.network.NetworkRegistry.class.getDeclaredField("instances");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var instances = (Map<ResourceLocation, ?>) f.get(null);
            ids.addAll(instances.keySet());
        } catch (Throwable t) {
            LogUtils.getLogger().warn("NEB: Failed to read Forge NetworkRegistry channels, payload index will be empty.", t);
        }
        // 原版自带的 payload channel，虽然量少但基本每局都发
        ids.add(new ResourceLocation("minecraft", "brand"));
        ids.add(new ResourceLocation("minecraft", "register"));
        ids.add(new ResourceLocation("minecraft", "unregister"));

        rebuild(new ArrayList<>(ids));
    }

    // ----------------------------------------客户端：从下发数据构建----------------------------------------

    public synchronized static void initFromBuffer(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("NEB: illegal payload index table size " + count);
        }
        var ids = new ArrayList<ResourceLocation>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readResourceLocation());
        }
        rebuild(ids);
    }

    /** 服务端下发用：VarInt count + count × ResourceLocation */
    public synchronized static void writeTable(FriendlyByteBuf buf) {
        int count = BY_INDEX.size() - 1;
        buf.writeVarInt(Math.max(count, 0));
        for (int i = 1; i <= count; i++) {
            buf.writeResourceLocation(BY_INDEX.get(i));
        }
    }

    private static void rebuild(ArrayList<ResourceLocation> ids) {
        initialized = false;
        BY_INDEX.clear();
        TO_INDEX.clear();
        BY_INDEX.add(PLACEHOLDER);
        for (var id : ids) {
            if (BY_INDEX.size() >= MAX_ENTRIES) {
                LogUtils.getLogger().warn("NEB: payload index table truncated at {} entries.", MAX_ENTRIES);
                break;
            }
            if (TO_INDEX.containsKey(id)) {
                continue;
            }
            TO_INDEX.put(id, BY_INDEX.size());
            BY_INDEX.add(id);
        }
        initialized = true;
        var logger = LogUtils.getLogger();
        if (logger.isDebugEnabled()) {
            logger.debug("NEB: payload index table built with {} entries.", BY_INDEX.size() - 1);
            TO_INDEX.forEach((id, i) -> logger.debug("  [{}] {}", i, id));
        }
    }

    // ----------------------------------------查询----------------------------------------

    /**
     * @return 索引，0 表示未被索引（需写完整 ResourceLocation）
     */
    public static int getIndex(ResourceLocation id) {
        if (!initialized) {
            return 0;
        }
        return TO_INDEX.getInt(id);
    }

    @Nullable
    public static ResourceLocation byIndex(int index) {
        if (!initialized || index <= 0 || index >= BY_INDEX.size()) {
            return null;
        }
        return BY_INDEX.get(index);
    }

    public static boolean ready() {
        return initialized;
    }

    public synchronized static void reset() {
        initialized = false;
        BY_INDEX.clear();
        TO_INDEX.clear();
    }
}
