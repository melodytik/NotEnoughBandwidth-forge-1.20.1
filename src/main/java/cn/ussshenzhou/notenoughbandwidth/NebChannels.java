package cn.ussshenzhou.notenoughbandwidth;

import net.minecraft.resources.ResourceLocation;

/**
 * NEB 自己的 CustomPayload channel。
 * <p>
 * 这些 channel <b>不走 Forge SimpleChannel</b>——SimpleChannel 会额外套一层 {@code fml:play} + discriminator，
 * 而 NEB 的聚合包本身就是为了省字节，所以直接用原版 {@link net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket}
 * 并在 PacketListener 层用 mixin 拦截。
 * <p>
 * path 刻意取单字母：channel id 每个聚合包都要发一次，{@code "neb:a"} 只占 6 字节。
 *
 * @author USS_Shenzhou
 */
public class NebChannels {
    /** 聚合包 */
    public static final ResourceLocation AGGREGATION = new ResourceLocation(ModConstants.MOD_ID, "a");
    /** 索引表下发（服务端 → 客户端） */
    public static final ResourceLocation INDEX = new ResourceLocation(ModConstants.MOD_ID, "i");
    /** 流量统计查询（客户端 → 服务端） */
    public static final ResourceLocation STAT_QUERY = new ResourceLocation(ModConstants.MOD_ID, "sq");
    /** 流量统计回复（服务端 → 客户端） */
    public static final ResourceLocation STAT_RESPOND = new ResourceLocation(ModConstants.MOD_ID, "sr");
}
