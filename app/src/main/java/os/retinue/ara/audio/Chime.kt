package os.retinue.ara.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import os.retinue.ara.R

/**
 * Short chime played when the wake phrase is heard. Loaded from
 * res/raw/chime.wav (easy to replace). SoundPool loads asynchronously, so the
 * owner creates it ahead of the first use.
 */
class Chime(context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    @Volatile private var loaded = false
    private val soundId: Int = soundPool.load(context, R.raw.chime, 1).also {
        soundPool.setOnLoadCompleteListener { _, _, status -> loaded = (status == 0) }
    }

    fun play() {
        if (loaded) soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }
}
