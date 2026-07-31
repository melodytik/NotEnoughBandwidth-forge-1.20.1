package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.chunk.DccTicker;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * @author USS_Shenzhou
 * <p>
 * Forge 1.20.1 port.
 */
@Mod(ModConstants.MOD_ID)
public class NotEnoughBandwidth {
    public static final Logger LOGGER = LogUtils.getLogger();

    public NotEnoughBandwidth() {
        ConfigHelper.loadConfig(new NotEnoughBandwidthConfig());
        MinecraftForge.EVENT_BUS.register(DccTicker.class);
        LOGGER.info("NotEnoughBandwidth (Forge 1.20.1) loaded.");
    }
}
