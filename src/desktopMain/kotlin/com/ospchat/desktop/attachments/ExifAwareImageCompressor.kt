package com.ospchat.desktop.attachments

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.ospchat.shared.data.attachments.ImageCompressor
import com.ospchat.shared.data.attachments.ImageCompressor.Companion.JPEG_QUALITY
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Desktop [ImageCompressor] that reads the EXIF `Orientation` tag via
 * `metadata-extractor` and bakes the rotation into the output pixels — matching
 * `AndroidImageCompressor`'s behavior so attachments picked from a desktop
 * render upright on the peer.
 *
 * Pipeline: read EXIF orientation → decode via `ImageIO` → apply orientation as
 * an `AffineTransform` while drawing into a target `BufferedImage` whose
 * dimensions account for 90°/270° swaps → scale to `maxEdge` → JPEG-encode.
 *
 * EXIF metadata itself is dropped on re-encode (ImageIO's JPEG writer doesn't
 * preserve it), so the resulting image carries no stale orientation tag.
 */
class ExifAwareImageCompressor : ImageCompressor {
    override fun compress(
        bytes: ByteArray,
        maxEdge: Int,
    ): ImageCompressor.Result {
        val orientation = readExifOrientation(bytes)

        val source =
            ByteArrayInputStream(bytes).use { ImageIO.read(it) }
                ?: error("ImageIO could not decode image bytes")

        val oriented = applyOrientation(source, orientation)
        val scaled = scaleToFit(oriented, maxEdge)

        val baos = ByteArrayOutputStream()
        encodeJpeg(scaled, baos, JPEG_QUALITY)

        return ImageCompressor.Result(
            bytes = baos.toByteArray(),
            width = scaled.width,
            height = scaled.height,
            mimeType = "image/jpeg",
        )
    }

    private fun readExifOrientation(bytes: ByteArray): Int =
        runCatching {
            ByteArrayInputStream(bytes).use { stream ->
                val metadata = ImageMetadataReader.readMetadata(stream)
                metadata
                    .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                    ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)
                    ?: ORIENTATION_NORMAL
            }
        }.getOrDefault(ORIENTATION_NORMAL)

    /**
     * Returns a new BufferedImage with the EXIF orientation baked in. For
     * orientations 1 (NORMAL) and any unknown value, the original is returned
     * unchanged. Output dimensions swap for 90°/270° rotations.
     */
    private fun applyOrientation(
        source: BufferedImage,
        orientation: Int,
    ): BufferedImage {
        if (orientation <= ORIENTATION_NORMAL || orientation > ORIENTATION_ROTATE_270) return source

        val w = source.width
        val h = source.height
        val (outW, outH) =
            when (orientation) {
                ORIENTATION_ROTATE_90, ORIENTATION_ROTATE_270, ORIENTATION_TRANSPOSE, ORIENTATION_TRANSVERSE -> h to w
                else -> w to h
            }

        // AffineTransform.rotate / scale / translate post-multiply, so the
        // LAST call here is the FIRST operation applied to source pixels. The
        // resulting transform on (x, y) is: rightmost-call * ... * leftmost-call * (x, y).
        val transform = AffineTransform()
        when (orientation) {
            ORIENTATION_FLIP_HORIZONTAL -> {
                transform.scale(-1.0, 1.0)
                transform.translate(-w.toDouble(), 0.0)
            }
            ORIENTATION_ROTATE_180 -> {
                transform.translate(w.toDouble(), h.toDouble())
                transform.rotate(Math.PI)
            }
            ORIENTATION_FLIP_VERTICAL -> {
                transform.scale(1.0, -1.0)
                transform.translate(0.0, -h.toDouble())
            }
            ORIENTATION_TRANSPOSE -> {
                transform.rotate(Math.PI / 2)
                transform.scale(1.0, -1.0)
            }
            ORIENTATION_ROTATE_90 -> {
                transform.translate(h.toDouble(), 0.0)
                transform.rotate(Math.PI / 2)
            }
            ORIENTATION_TRANSVERSE -> {
                transform.scale(-1.0, 1.0)
                transform.translate(-h.toDouble(), 0.0)
                transform.translate(0.0, w.toDouble())
                transform.rotate(3 * Math.PI / 2)
            }
            ORIENTATION_ROTATE_270 -> {
                transform.translate(0.0, w.toDouble())
                transform.rotate(3 * Math.PI / 2)
            }
        }

        val dest = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
        val g = dest.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(source, transform, null)
        } finally {
            g.dispose()
        }
        return dest
    }

    private fun scaleToFit(
        source: BufferedImage,
        maxEdge: Int,
    ): BufferedImage {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val ratio = maxEdge.toDouble() / longest
        val targetW = (source.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (source.height * ratio).toInt().coerceAtLeast(1)

        val dest = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val g = dest.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY,
            )
            g.drawImage(source, 0, 0, targetW, targetH, null)
        } finally {
            g.dispose()
        }
        return dest
    }

    private fun encodeJpeg(
        image: BufferedImage,
        out: ByteArrayOutputStream,
        quality: Int,
    ) {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
        val ios = ImageIO.createImageOutputStream(out)
        try {
            writer.output = ios
            writer.write(null, IIOImage(image, null, null), params)
        } finally {
            writer.dispose()
            ios.close()
        }
    }

    private companion object {
        // EXIF Orientation tag values per the TIFF/EXIF spec. Mirrored from
        // androidx.exifinterface.media.ExifInterface; kept inline because
        // we don't pull in that Android-only dependency on desktop.
        const val ORIENTATION_NORMAL = 1
        const val ORIENTATION_FLIP_HORIZONTAL = 2
        const val ORIENTATION_ROTATE_180 = 3
        const val ORIENTATION_FLIP_VERTICAL = 4
        const val ORIENTATION_TRANSPOSE = 5
        const val ORIENTATION_ROTATE_90 = 6
        const val ORIENTATION_TRANSVERSE = 7
        const val ORIENTATION_ROTATE_270 = 8
    }
}
