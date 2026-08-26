package vn.apero.armeasure

import android.graphics.Bitmap
import android.net.Uri

/**
 * The one port that has to cross an Activity boundary this module does not control the launch
 * of: where a finished photo-measurement's picture ends up. There is no DI framework in this
 * repo (verified: zero Koin/Hilt references), so a plain interface plus [ArMeasureConfig] is the
 * simplest thing that works.
 *
 * A malicious or buggy implementation receives the user's photo bitmap — this is a trust
 * boundary. Only install an implementation you trust.
 */
fun interface MeasurementImageSaver {
    suspend fun save(bitmap: Bitmap, fileName: String): Uri?
}

/**
 * The module's only process-wide configuration point.
 *
 * Deliberately **not** a bare mutable `var`: a bare public `var` is the same smell this codebase
 * already removed once, when the old `ArWarmup` top-level state became `internal object
 * ArWarmupGate` with a narrow read API instead. [setImageSaver] is a one-shot initializer the
 * host calls exactly once, normally from `Application.onCreate` — never a per-screen or
 * per-Activity setting, and never read back as a mutable field a caller could reassign at will.
 * Anything that is instead scoped to one Activity (e.g. a future per-launch option) belongs in
 * that Activity's `Intent` extras, not here.
 */
object ArMeasureConfig {

    @Volatile
    private var saver: MeasurementImageSaver? = null

    /**
     * `null` until a host calls [setImageSaver]: the module then falls back to saving into
     * `Pictures/<app label>` itself (wired in phase 08). Internal — only this module's own save
     * path reads it back.
     */
    internal val imageSaver: MeasurementImageSaver?
        get() = saver

    /**
     * Installs the host's [MeasurementImageSaver], once. Call from `Application.onCreate` —
     * before any [vn.apero.armeasure.photo.presentation.PhotoMeasureScreen] save can run, never
     * lazily from inside a screen. Calling this more than once is almost certainly a bug (it
     * silently changes who receives the user's photo bitmap mid-process), so it throws rather
     * than swapping the implementation out from under an already-running screen.
     */
    fun setImageSaver(saver: MeasurementImageSaver) {
        check(this.saver == null) { "ArMeasureConfig.setImageSaver was already called once" }
        this.saver = saver
    }
}
