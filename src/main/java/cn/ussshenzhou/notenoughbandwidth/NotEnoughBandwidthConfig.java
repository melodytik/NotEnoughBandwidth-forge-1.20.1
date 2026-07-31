package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.config.TConfig;
import com.google.gson.annotations.Expose;
import com.mojang.logging.LogUtils;
import net.minecraft.util.Mth;

import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * @author USS_Shenzhou
 * <p>
 * Forge 1.20.1 port.
 * <p>
 * Packet type keys follow the vanilla-ish naming used by 1.21+: a clientbound packet class such as
 * {@code ClientboundCommandSuggestionsPacket} maps to {@code minecraft:command_suggestions}, while
 * custom payloads are keyed by their channel {@code ResourceLocation}.
 */
public class NotEnoughBandwidthConfig implements TConfig {

    public boolean compatibleMode = false;
    public HashSet<String> blackList = new HashSet<>() {{
        add("minecraft:command_suggestions");
        add("minecraft:commands");
        add("minecraft:player_info_update");
        add("minecraft:player_info_remove");
    }};
    public boolean debugLog = false;
    public boolean dccEnabled = true;
    public int contextLevel = 23;
    public int dccSizeLimit = 60;
    public int dccDistance = 5;
    public int dccTimeout = 60;
    public String maxPacketSize = "4MB";
    public HashSet<String> playersDoNotUseContext = new HashSet<>() {{
        add("00000000-0000-0000-0000-000000000000");
    }};

    /**
     * Packets that must never be aggregated, no matter what the user configures.
     */
    @Expose(serialize = false, deserialize = false)
    public static final HashSet<String> COMMON_BLACK_LIST = new HashSet<>() {{
        // our own packets —— 必须与 NebChannels 完全一致，否则聚合包自己会被再次聚合，无限递归
        add(NebChannels.AGGREGATION.toString());
        add(NebChannels.INDEX.toString());
        add(NebChannels.STAT_QUERY.toString());
        add(NebChannels.STAT_RESPOND.toString());
        // vanilla lifecycle packets that must not be delayed
        add("minecraft:login");
        add("minecraft:disconnect");
        add("minecraft:keep_alive");
        // channel negotiation, Forge relies on these arriving untouched
        add("minecraft:register");
        add("minecraft:unregister");
        add("fml:handshake");
        add("fml:play");
    }};

    public static NotEnoughBandwidthConfig get() {
        return ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class);
    }

    public static boolean skipType(String type) {
        var cfg = get();
        if (cfg == null) {
            return true;
        }
        return COMMON_BLACK_LIST.contains(type) || (cfg.compatibleMode && cfg.blackList.contains(type));
    }

    public int getContextLevel() {
        return Mth.clamp(contextLevel, 21, 25);
    }

    @Expose(serialize = false, deserialize = false)
    private int maxPacketSizeByte = -1;

    public int getMaxPacketSize() {
        if (maxPacketSizeByte == -1) {
            maxPacketSizeByte = parseByteSize(maxPacketSize);
            int min = parseByteSize("2MB");
            int max = parseByteSize("64MB");
            if (maxPacketSizeByte < min || maxPacketSizeByte > max) {
                LogUtils.getLogger().error("maxPacketSize should be between 2MB and 64MB");
            }
            maxPacketSizeByte = Mth.clamp(maxPacketSizeByte, min, max);
        }
        return maxPacketSizeByte;
    }

    private static int parseByteSize(String s) {
        var matcher = Pattern.compile("^([\\d.]+)\\s*(B|KB|MB)?$", Pattern.CASE_INSENSITIVE).matcher(s.trim());
        if (!matcher.matches()) {
            LogUtils.getLogger().error("NEB: Invalid packet size: {} , use default 4MB instead.", s);
            return parseByteSize("4MB");
        }
        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2);
        if (unit == null || "B".equalsIgnoreCase(unit)) {
            return (int) value;
        }
        return (int) switch (unit.toUpperCase()) {
            case "KB" -> value * 1024;
            case "MB" -> value * 1024 * 1024;
            default -> {
                LogUtils.getLogger().error("NEB: Invalid packet size: {} , use default 4MB instead.", s);
                yield parseByteSize("4MB");
            }
        };
    }
}
