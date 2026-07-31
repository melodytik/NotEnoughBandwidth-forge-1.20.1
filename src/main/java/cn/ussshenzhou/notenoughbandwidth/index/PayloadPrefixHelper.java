package cn.ussshenzhou.notenoughbandwidth.index;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * CustomPayload 的紧凑 channel 前缀读写。
 *
 * <pre>
 * ┌--------- 1~3 bytes ---------┬------------ 可选 ------------┐
 * │      index (VarInt)         │  index==0 时紧跟完整 RL(UTF)  │
 * └-----------------------------┴------------------------------┘
 * </pre>
 * <p>
 * 相比原版直接写 {@code writeResourceLocation}（如 {@code "somemod:some_long_packet_name"} 要 30 字节），
 * 索引命中时只要 1~2 字节。
 *
 * @author USS_Shenzhou, nutant233
 */
public class PayloadPrefixHelper {

    public static void write(ResourceLocation id, FriendlyByteBuf buf) {
        int index = PayloadIndexManager.getIndex(id);
        buf.writeVarInt(index);
        if (index == 0) {
            buf.writeResourceLocation(id);
        }
    }

    /**
     * @return 解出的 channel id；索引失效时返回 null（调用方应跳过该子包）
     */
    @Nullable
    public static ResourceLocation read(FriendlyByteBuf buf) {
        int index = buf.readVarInt();
        if (index == 0) {
            return buf.readResourceLocation();
        }
        return PayloadIndexManager.byIndex(index);
    }
}
