package cn.ussshenzhou.notenoughbandwidth.util;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelPipeline;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * 反射遍历 netty pipeline，定位原版的 PacketEncoder / PacketDecoder。
 * <p>
 * 1.20.1 差异：PacketEncoder / PacketDecoder 均无泛型参数（NeoForge 26.x 才有 {@code PacketEncoder<T>}）。
 *
 * @author USS_Shenzhou
 */
public class DefaultChannelPipelineHelper {

    private static final Field HEAD;
    private static final Field TAIL;
    private static final Field NEXT;

    static {
        try {
            HEAD = DefaultChannelPipeline.class.getDeclaredField("head");
            HEAD.setAccessible(true);
            TAIL = DefaultChannelPipeline.class.getDeclaredField("tail");
            TAIL.setAccessible(true);
            // head 的类型是 HeadContext，其父类 AbstractChannelHandlerContext 持有 next 字段
            NEXT = ((Class<?>) DefaultChannelPipeline.class.getDeclaredField("head")
                    .getType().getAnnotatedSuperclass().getType()).getDeclaredField("next");
            NEXT.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public static PacketEncoder getPacketEncoder(DefaultChannelPipeline pipeline) {
        return find(pipeline, PacketEncoder.class);
    }

    @Nullable
    public static PacketDecoder getPacketDecoder(DefaultChannelPipeline pipeline) {
        return find(pipeline, PacketDecoder.class);
    }

    @Nullable
    private static <T> T find(DefaultChannelPipeline pipeline, Class<T> target) {
        try {
            Object head = HEAD.get(pipeline);
            Object tail = TAIL.get(pipeline);
            var ctx = (ChannelHandlerContext) NEXT.get(head);
            while (ctx != null && ctx != tail) {
                if (target.isInstance(ctx.handler())) {
                    return target.cast(ctx.handler());
                }
                ctx = (ChannelHandlerContext) NEXT.get(ctx);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
