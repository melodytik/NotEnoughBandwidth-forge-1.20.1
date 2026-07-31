package cn.ussshenzhou.notenoughbandwidth.zstd;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

import java.io.Closeable;
import java.nio.ByteBuffer;

/**
 * @author USS_Shenzhou
 */
public class Context implements Closeable {
    private final ZstdCompressCtx compressCtx;
    private final ZstdDecompressCtx decompressCtx;
    private final boolean useContext;

    public Context(boolean useContext) {
        var cfg = NotEnoughBandwidthConfig.get();
        int level = cfg != null ? cfg.getContextLevel() : 23;
        compressCtx = new ZstdCompressCtx();
        compressCtx.setLevel(3);
        compressCtx.setContentSize(false);
        compressCtx.setMagicless(true);
        compressCtx.setWindowLog(level);
        decompressCtx = new ZstdDecompressCtx();
        decompressCtx.setMagicless(true);
        this.useContext = useContext;
    }

    public ByteBuffer compress(ByteBuffer raw) {
        if (useContext) {
            int maxDstSize = (int) Zstd.compressBound(raw.remaining());
            var dst = ByteBuffer.allocateDirect(maxDstSize);
            // 用 END 而非 FLUSH：每个聚合包都是独立的、自包含的 zstd 帧。
            // 这样即便某个包解码出错，也只会丢掉那一批，不会让整条流的压缩上下文永久错位。
            compressCtx.compressDirectByteBufferStream(dst, raw, EndDirective.END);
            dst.flip();
            return dst;
        }
        return compressCtx.compress(raw);
    }

    public ByteBuffer decompress(ByteBuffer compressed, int originalSize) {
        var dst = ByteBuffer.allocateDirect(originalSize);
        if (useContext) {
            decompressCtx.decompressDirectByteBufferStream(dst, compressed);
        } else {
            decompressCtx.decompress(dst, compressed);
        }
        dst.flip();
        return dst;
    }

    @Override
    public void close() {
        compressCtx.close();
        decompressCtx.close();
    }
}
