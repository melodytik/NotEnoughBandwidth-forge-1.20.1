package cn.ussshenzhou.notenoughbandwidth.aggregation;

/**
 * @author USS_Shenzhou
 */
public class AggregationFlushHelper {
    /** 聚合窗口，一个 tick 的时长 */
    public static int getFlushPeriodInMilliseconds() {
        return 20;
    }

    public static int getFlushCountInSeconds() {
        return Math.max(1000 / getFlushPeriodInMilliseconds(), 1);
    }

    public static int getThresholdCount1s() {
        return 20 * 2;
    }

    /**
     * 窗口内累计到这个字节数就提前 flush，避免单个聚合包过大。
     * <p>
     * 1.20.1 原版 {@code ClientboundCustomPayloadPacket} 硬限 1MB（我们用 mixin 放宽了），
     * 但仍留出余量，同时避免大包造成明显的传输抖动。
     */
    public static int getEarlyFlushThreshold() {
        return 900 * 1024;
    }
}
