package cn.ussshenzhou.notenoughbandwidth.client;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Alt+N 打开流量统计面板。
 * <p>
 * 1.20.1 的 Forge 把两类事件放在<b>不同总线</b>上：{@code RegisterKeyMappingsEvent} 在 MOD 总线，
 * {@code InputEvent.Key} 在 FORGE 总线。一个类只能挂一个 {@code @EventBusSubscriber}，
 * 所以按键处理拆到内部类 {@link Handler}。
 * <p>
 * 另外 1.20.1 的 {@code KeyMapping} 分类是一个语言键字符串（26.x 才改成 {@code KeyMapping.Category}）。
 *
 * @author USS_Shenzhou
 */
@Mod.EventBusSubscriber(modid = ModConstants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModKey {

    public static final String CATEGORY = "key.categories." + ModConstants.MOD_ID;

    public static final KeyMapping STAT = new KeyMapping(
            "key." + ModConstants.MOD_ID + ".stat",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.ALT,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            CATEGORY
    );

    @SubscribeEvent
    public static void onRegisterKey(RegisterKeyMappingsEvent event) {
        event.register(STAT);
    }

    @Mod.EventBusSubscriber(modid = ModConstants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class Handler {
        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            // consumeClick 必须无条件调用，否则按键状态会一直累积
            if (STAT.consumeClick()) {
                var mc = Minecraft.getInstance();
                // 只在游戏内（没有其它 Screen 打开时）响应，避免在聊天框里打字触发
                if (mc.screen == null && mc.level != null) {
                    mc.setScreen(new StatScreen());
                }
            }
        }
    }
}
