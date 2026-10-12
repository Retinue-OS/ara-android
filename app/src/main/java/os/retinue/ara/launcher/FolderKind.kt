package os.retinue.ara.launcher

import android.content.pm.ApplicationInfo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The standard folder types offered when a folder is made. The UI gives each a
 * symbol and a default name; [categories] are the Android app categories
 * ([ApplicationInfo.category], declared by apps in their manifest) whose apps
 * the type suggests. Stored by [key]: an unknown key reads as [General], so a
 * type added later does not cost an older version the folder.
 */
@Serializable(with = FolderKindSerializer::class)
enum class FolderKind(val key: String, val categories: Set<Int> = emptySet()) {
    General("general"),
    Chat("chat"),
    Social("social", setOf(ApplicationInfo.CATEGORY_SOCIAL)),
    Media("media", setOf(ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO)),
    Photos("photos", setOf(ApplicationInfo.CATEGORY_IMAGE)),
    Games("games", setOf(ApplicationInfo.CATEGORY_GAME)),
    News("news", setOf(ApplicationInfo.CATEGORY_NEWS)),
    Travel("travel", setOf(ApplicationInfo.CATEGORY_MAPS)),
    Work("work", setOf(ApplicationInfo.CATEGORY_PRODUCTIVITY)),
    Tools("tools"),
    Shopping("shopping"),
    Finance("finance"),
    Health("health"),
    SmartHome("smart_home"),
    ;

    /** Whether an app of Android [category] fits this type (apps without a category never do). */
    fun suggests(category: Int): Boolean = category in categories

    companion object {
        fun fromKey(key: String?): FolderKind = entries.firstOrNull { it.key == key } ?: General
    }
}

/** [FolderKind] as its [FolderKind.key]. */
object FolderKindSerializer : KSerializer<FolderKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("os.retinue.ara.launcher.FolderKind", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: FolderKind) = encoder.encodeString(value.key)

    override fun deserialize(decoder: Decoder): FolderKind = FolderKind.fromKey(decoder.decodeString())
}
