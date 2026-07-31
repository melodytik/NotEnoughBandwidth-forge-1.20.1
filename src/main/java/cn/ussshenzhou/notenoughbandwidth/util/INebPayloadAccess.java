package cn.ussshenzhou.notenoughbandwidth.util;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 由 mixin 注入到 {@code Clientbound/ServerboundCustomPayloadPacket} 的 duck-typing 接口。
 * <p>
 * 原版 {@code getData()} 返回的是 <b>copy</b>，在聚合热路径上每包多一次内存拷贝，
 * 这里直接暴露内部 buf；同时挂一个 bakedSize 供流量统计区分「聚合包」与普通包。
 *
 * @author USS_Shenzhou
 */
public interface INebPayloadAccess {

    /** 不拷贝的内部数据缓冲，调用方不得修改其 readerIndex */
    FriendlyByteBuf neb$rawData();

    /**
     * 聚合包解压前的净荷大小。
     * <p>
     * 返回 0 表示「这不是 NEB 聚合包」——刻意不用 -1 作哨兵值，因为 mixin 的 {@code @Unique}
     * 字段初始化器在部分环境下不可靠，让 JVM 的默认零值直接承担哨兵语义更稳。
     */
    int neb$getBakedSize();

    void neb$setBakedSize(int size);
}
