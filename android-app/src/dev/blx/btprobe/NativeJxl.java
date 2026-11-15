package dev.blx.btprobe;

import android.graphics.Bitmap;
import android.util.Size;
import com.awxkee.jxlcoder.*;

/** Explicit software codec. No MediaCodec, system JXL decoder, hardware Bitmap or GPU. */
final class NativeJxl {
    static byte[] encode(Bitmap pixels, int quality) {
        return JxlCoder.INSTANCE.encode(pixels, JxlChannelsConfiguration.RGB,
            JxlCompressionOption.LOSSY, JxlEffort.TORTOISE, quality, JxlDecodingSpeed.SLOWEST);
    }
    static Size size(byte[] bytes) { return JxlCoder.INSTANCE.getSize(bytes); }
    static Bitmap decodeSampled(byte[] bytes, int edge) {
        return JxlCoder.INSTANCE.decodeSampled(bytes, edge, edge, PreferredColorConfig.RGBA_8888,
            ScaleMode.FIT, JxlResizeFilter.MITCHELL_NETRAVALI);
    }
    static Bitmap decode(byte[] bytes) {
        return JxlCoder.INSTANCE.decode(bytes, PreferredColorConfig.RGBA_8888, ScaleMode.FIT);
    }
}
