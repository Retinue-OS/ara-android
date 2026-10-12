package os.retinue.ara.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picked image made into a folder icon but not stored yet: the PNG and its preview. */
class PreparedIcon(val png: ByteArray, val preview: ImageBitmap)

/**
 * Custom folder icons. An image picked from the photo picker or any document
 * provider is cropped to its centre square, scaled to [ICON_PX] and kept as a
 * PNG in app storage under a generated name ([LauncherEntry.Folder.icon]).
 * The picked file itself is not kept or referenced, so it may move or vanish.
 */
object FolderIcons {

    /** Edge of a stored icon: about three times the largest size one is drawn at. */
    private const val ICON_PX = 192
    private const val MAX_DECODE_PX = 4096
    private const val DIR = "folder-icons"

    private val cache = LruCache<String, ImageBitmap>(32)

    private fun file(context: Context, name: String): File? =
        if (LauncherEntry.Folder.isIconName(name)) File(File(context.filesDir, DIR), name) else null

    /** Decode the image at [uri] and make it an icon; throws when it is no readable image. */
    suspend fun prepare(context: Context, uri: Uri): PreparedIcon = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        // ImageDecoder applies the EXIF orientation; decode in software (to crop and
        // scale) and at no more resolution than the icon needs — nor, for a very
        // long panorama, more than MAX_DECODE_PX along its length.
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val shorter = minOf(info.size.width, info.size.height)
            val longer = maxOf(info.size.width, info.size.height)
            decoder.setTargetSampleSize(maxOf(shorter / ICON_PX, longer / MAX_DECODE_PX, 1))
        }
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val icon = if (side == ICON_PX) square else Bitmap.createScaledBitmap(square, ICON_PX, ICON_PX, true)
        val png = ByteArrayOutputStream().use { out ->
            if (!icon.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("The image could not be converted")
            out.toByteArray()
        }
        PreparedIcon(png, icon.asImageBitmap())
    }

    /** Store a prepared icon under a new name; returns the name. */
    suspend fun store(context: Context, png: ByteArray): String = withContext(Dispatchers.IO) {
        val name = UUID.randomUUID().toString() + ".png"
        val dest = file(context, name) ?: throw IOException("Bad icon name")
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "$name.part")
        try {
            tmp.writeBytes(png)
            if (!tmp.renameTo(dest)) throw IOException("Could not store the icon")
        } finally {
            tmp.delete()
        }
        name
    }

    /** The stored icon [name], cached; null when it is missing or unreadable (the type's symbol is drawn then). */
    suspend fun load(context: Context, name: String): ImageBitmap? {
        cache.get(name)?.let { return it }
        val f = file(context, name) ?: return null
        return withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { cache.put(name, it) }
        }
    }

    /** Delete stored icons that nothing uses any more. */
    suspend fun delete(context: Context, names: Collection<String>) = withContext(Dispatchers.IO) {
        names.forEach { name ->
            cache.remove(name)
            file(context, name)?.delete()
        }
    }

    /**
     * Delete what an interrupted save left behind: icon files no folder uses
     * (stored, but the process died before the launcher edit was committed)
     * and partial writes. Only files older than [before] are touched, so an
     * icon being saved right now is never swept away.
     */
    suspend fun sweep(context: Context, inUse: Set<String>, before: Long) {
        withContext(Dispatchers.IO) {
            File(context.filesDir, DIR).listFiles()?.forEach { f ->
                if (f.name !in inUse && f.lastModified() < before) {
                    cache.remove(f.name)
                    f.delete()
                }
            }
        }
    }
}
