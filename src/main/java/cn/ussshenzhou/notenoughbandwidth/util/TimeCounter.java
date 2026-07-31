package cn.ussshenzhou.notenoughbandwidth.util;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.Util;

/**
 * 滑动窗口计数器。1.20.1 的 Util 在 {@code net.minecraft.Util}（26.x 为 net.minecraft.util.Util）。
 *
 * @author USS_Shenzhou
 */
public class TimeCounter {
    private final Long2IntOpenHashMap container = new Long2IntOpenHashMap();
    private final int windowsSizeMs;

    public TimeCounter(int windowsSizeMs) {
        this.windowsSizeMs = windowsSizeMs;
    }

    public TimeCounter() {
        this(2000);
    }

    private synchronized void update() {
        long now = Util.getMillis();
        container.keySet().removeIf(then -> now - then > windowsSizeMs);
    }

    public synchronized void put(int value) {
        update();
        long now = Util.getMillis();
        // 同一毫秒内多次记录需累加，原版直接 put 会丢数据
        container.addTo(now, value);
    }

    public synchronized double averageIn1s() {
        update();
        long sum = 0;
        for (int v : container.values()) {
            sum += v;
        }
        return sum / (double) windowsSizeMs * 1000;
    }
}
