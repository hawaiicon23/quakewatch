package dev.blx.btprobe

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.*

/** Bounded pixel-only, opaque SDR/sRGB images. Source metadata never enters the encoder. */
internal object ChatImage {
    const val MAX_EDGE=1024
    const val DEFAULT_EDGE=256
    const val DEFAULT_COMPRESSION=80
    data class Encoded(val bytes:ByteArray,val width:Int,val height:Int,val compression:Int,val edge:Int)

    @JvmStatic fun load(context:Context,uri:Uri):Bitmap {
        val resolver=context.contentResolver
        val signature=ByteArray(12)
        resolver.openInputStream(uri).use { it?.read(signature) }
        val jxl=isRawJxl(signature)||signature.contentEquals(byteArrayOf(0,0,0,12,0x4a,0x58,0x4c,0x20,13,10,0x87.toByte(),10))
        val decoded=if(jxl) {
            val bytes=java.io.ByteArrayOutputStream()
            resolver.openInputStream(uri).use { stream ->
                requireNotNull(stream){"Could not open image"}
                val chunk=ByteArray(8192)
                while(true) {
                    val count=stream.read(chunk);if(count<0)break
                    require(bytes.size()+count<=8*1024*1024){"Source JPEG XL exceeds 8 MiB"}
                    bytes.write(chunk,0,count)
                }
            }
            val data=bytes.toByteArray()
            val size=NativeJxl.size(data) ?: throw IOException("Invalid JPEG XL")
            require(size.width in 1..8192&&size.height in 1..8192){"Source JPEG XL dimensions exceed 8192 px"}
            NativeJxl.decodeSampled(data,min(MAX_EDGE,max(size.width,size.height)))
        } else if(Build.VERSION.SDK_INT>=28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,uri)) { decoder,info,_ ->
            val scale=min(1f,MAX_EDGE.toFloat()/max(info.size.width,info.size.height))
            decoder.setTargetSize(max(1,(info.size.width*scale).roundToInt()),max(1,(info.size.height*scale).roundToInt()))
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        } else {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            resolver.openInputStream(uri).use {BitmapFactory.decodeStream(it,null,bounds)}
            require(bounds.outWidth>0&&bounds.outHeight>0){"Unsupported image"}
            val options=BitmapFactory.Options().apply {
                inSampleSize=1
                while(max(bounds.outWidth,bounds.outHeight)/inSampleSize>MAX_EDGE*2) inSampleSize*=2
                inPreferredConfig=Bitmap.Config.ARGB_8888
                inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val raw=resolver.openInputStream(uri).use {BitmapFactory.decodeStream(it,null,options)} ?: throw IOException("Could not read image")
            // ImageDecoder applies orientation itself; BitmapFactory on Android 8 needs it baked in.
            val orientation=resolver.openInputStream(uri).use { stream ->
                if(stream==null)ExifInterface.ORIENTATION_NORMAL else runCatching{ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
            }
            val matrix=Matrix().apply { when(orientation) {
                2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
                5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f)
                7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f)
            }}
            if(matrix.isIdentity)raw else Bitmap.createBitmap(raw,0,0,raw.width,raw.height,matrix,true).also{raw.recycle()}
        }
        return try { pixels(decoded,MAX_EDGE) } finally {decoded.recycle()}
    }
    private fun pixels(source:Bitmap,edge:Int):Bitmap {
        require(edge in 64..MAX_EDGE){"Choose 64–1024 pixels"}
        val ratio=min(1f,edge.toFloat()/max(source.width,source.height))
        val output=Bitmap.createBitmap(max(1,(source.width*ratio).roundToInt()),max(1,(source.height*ratio).roundToInt()),Bitmap.Config.ARGB_8888)
        output.setHasAlpha(false)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(source,null,Rect(0,0,output.width,output.height),Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return output
    }
    @JvmStatic fun encode(source:Bitmap,edge:Int,compression:Int):Encoded {
        require(compression in 0..100)
        val clean=pixels(source,edge)
        return try {
            // Slider increases compression: quality 85 down to 15. Effort stays high (9).
            val bytes=NativeJxl.encode(clean,85-(compression*.7f).roundToInt())
            check(isRawJxl(bytes)){"Encoder did not produce a minimal JPEG XL codestream"}
            Encoded(bytes,clean.width,clean.height,compression,edge)
        } finally {clean.recycle()}
    }
    @JvmStatic fun isRawJxl(data:ByteArray)=data.size>=2&&data[0]==0xff.toByte()&&data[1]==0x0a.toByte()
    @JvmStatic fun decode(data:ByteArray?):Bitmap? {
        if(data==null||data.isEmpty()||data.size>ChatProtocol.MAX_IMAGE)return null
        return try {
            if(isRawJxl(data)) {
                val size=NativeJxl.size(data) ?: return null
                if(size.width !in 1..MAX_EDGE||size.height !in 1..MAX_EDGE)return null
                NativeJxl.decode(data)
            } else {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(data,0,data.size,bounds)
                if(bounds.outMimeType!="image/jpeg"||bounds.outWidth !in 1..70||bounds.outHeight !in 1..70)return null
                BitmapFactory.decodeByteArray(data,0,data.size)
            }
        } catch(_:Exception){null} catch(_:LinkageError){null} catch(_:OutOfMemoryError){null}
    }
}
