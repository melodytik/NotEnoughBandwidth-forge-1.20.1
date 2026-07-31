package cn.ussshenzhou.notenoughbandwidth.client;

import cn.ussshenzhou.notenoughbandwidth.stat.StatPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager.*;

/**
 * 流量统计面板。Alt+N 打开。
 * <p>
 * 1.20.1 没有 26.x 的 {@code extractRenderState}/{@code GuiGraphicsExtractor} 那套
 * render-state 抽取管线，直接用 {@link #render(GuiGraphics, int, int, float)} 即可。
 *
 * @author USS_Shenzhou
 */
public class StatScreen extends Screen {

    private static final String CLIENT = "Client";
    private static final String SERVER = "Server";
    private static final String ACTUAL = "Actual Transmission";
    private static final String RAW = "Raw Payload";

    private String actualC = "-";
    private String rawC = "-";
    private String ratioC = "-";
    private String actualS = "-";
    private String rawS = "-";
    private String ratioS = "-";

    private int tick = 0;

    public StatScreen() {
        super(Component.literal("NEB Traffic"));
    }

    @Override
    public boolean isPauseScreen() {
        // 必须返回 false：这个面板就是用来看实时流量的，暂停了就没数据可看了
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (tick % 10 == 0) {
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.send(StatPayloads.query());
            }

            actualC = line(
                    (int) LOCAL.inboundSpeedBaked().averageIn1s(), LOCAL.inboundBytesBaked().get(),
                    (int) LOCAL.outboundSpeedBaked().averageIn1s(), LOCAL.outboundBytesBaked().get());
            rawC = line(
                    (int) LOCAL.inboundSpeedRaw().averageIn1s(), LOCAL.inboundBytesRaw().get(),
                    (int) LOCAL.outboundSpeedRaw().averageIn1s(), LOCAL.outboundBytesRaw().get());
            ratioC = ratio(
                    LOCAL.inboundBytesBaked().get(), LOCAL.inboundBytesRaw().get(),
                    LOCAL.outboundBytesBaked().get(), LOCAL.outboundBytesRaw().get());

            actualS = line(
                    (int) inboundSpeedBakedServer, inboundBytesBakedServer,
                    (int) outboundSpeedBakedServer, outboundBytesBakedServer);
            rawS = line(
                    (int) inboundSpeedRawServer, inboundBytesRawServer,
                    (int) outboundSpeedRawServer, outboundBytesRawServer);
            ratioS = ratio(
                    inboundBytesBakedServer, inboundBytesRawServer,
                    outboundBytesBakedServer, outboundBytesRawServer);
        }
        tick++;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x80000000);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, CLIENT, 10, 10, 0xFFFFFF);
        graphics.drawString(font, ACTUAL, 10, 30, 0xAAAAAA);
        graphics.drawString(font, actualC, 10, 40, 0xFFFFFF);
        graphics.drawString(font, RAW, 10, 60, 0xAAAAAA);
        graphics.drawString(font, rawC, 10, 70, 0xFFFFFF);
        graphics.drawString(font, ratioC, 10, 90, 0xFFFFFF);

        graphics.drawString(font, SERVER, 10, 120, 0xFFFFFF);
        graphics.drawString(font, ACTUAL, 10, 140, 0xAAAAAA);
        graphics.drawString(font, actualS, 10, 150, 0xFFFFFF);
        graphics.drawString(font, RAW, 10, 170, 0xAAAAAA);
        graphics.drawString(font, rawS, 10, 180, 0xFFFFFF);
        graphics.drawString(font, ratioS, 10, 200, 0xFFFFFF);
    }

    private static String line(int inSpeed, long inTotal, int outSpeed, long outTotal) {
        return "\u2193 Inbound  " + readableSpeed(inSpeed) + "  Total  " + readableSize(inTotal)
                + "    \u2191 Outbound  " + readableSpeed(outSpeed) + "  Total  " + readableSize(outTotal);
    }

    private static String ratio(long inBaked, long inRaw, long outBaked, long outRaw) {
        return "Ratio                            " + percent(inBaked, inRaw)
                + "                                        " + percent(outBaked, outRaw);
    }

    /**
     * raw 为 0 时（刚进服务器、还没有任何流量）直接除会得到 NaN，显示成 "-" 更干净。
     */
    private static String percent(long baked, long raw) {
        if (raw <= 0) {
            return "-";
        }
        return String.format("%.2f%%", 100d * baked / raw);
    }

    private static String readableSpeed(int bytes) {
        if (bytes < 1000) {
            return bytes + " \u00a77Bytes/S\u00a7r";
        } else if (bytes < 1000 * 1000) {
            return String.format("%.1f \u00a77KiB/S\u00a7r", bytes / 1024f);
        } else {
            return String.format("%.2f \u00a77MiB/S\u00a7r", bytes / (1024 * 1024f));
        }
    }

    private static String readableSize(long bytes) {
        if (bytes < 1000) {
            return bytes + " \u00a77Bytes\u00a7r";
        } else if (bytes < 1000L * 1000) {
            return String.format("%.1f \u00a77KiB\u00a7r", bytes / 1024d);
        } else if (bytes < 1000L * 1000 * 1000) {
            return String.format("%.2f \u00a77MiB\u00a7r", bytes / (1024 * 1024d));
        } else {
            return String.format("%.2f \u00a77GiB\u00a7r", bytes / (1024 * 1024 * 1024d));
        }
    }
}
