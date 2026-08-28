package vn.apero.armeasure

import android.content.Context
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
 * Wraps a base [Context] so this module's own Activities render in the language the host app is
 * actually showing.
 *
 * Needed because an in-app language picker is not the same thing as the device language. A host
 * that switches language by wrapping each Activity's context — `attachBaseContext` plus
 * `createConfigurationContext`, which is what `AIP936-AIHomeDesign`'s `BaseComposeActivity` does —
 * only reaches Activities that extend *its* base class. This module's `ArCameraActivity` and
 * `ArPhotoActivity` do not, so without this hook they fall back to the **device** locale while the
 * rest of the app is in the user's chosen one: the hub, embedded in the host's own Activity, comes
 * up translated and the screen it opens comes up in English.
 *
 * A host that instead uses the platform's per-app languages
 * (`AppCompatDelegate.setApplicationLocales`) needs none of this — that applies process-wide, to
 * every Activity, and this hook can be left unset.
 */
fun interface ArMeasureContextWrapper {
    fun wrap(base: Context): Context
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

    @Volatile
    private var contextWrapper: ArMeasureContextWrapper? = null

    /**
     * `null` until a host calls [setContextWrapper], in which case the module's Activities take the
     * base context unchanged — correct for a host with no in-app language picker, and for one using
     * the platform's per-app languages.
     */
    internal fun wrapContext(base: Context): Context =
        contextWrapper?.let { runCatching { it.wrap(base) }.getOrDefault(base) } ?: base

    /**
     * Installs the host's [ArMeasureContextWrapper], once, from `Application.onCreate`.
     *
     * Typically one line delegating to whatever the host's own base Activity already does, e.g.
     * `ArMeasureConfig.setContextWrapper { base -> localeManager.syncLocale(); localeManager.updateLocale(base) }`.
     *
     * Throws on a second call, for the same reason [setImageSaver] does: swapping it mid-process
     * would leave already-created Activities on the old locale and new ones on another, which reads
     * as a random half-translated app rather than as the misconfiguration it is.
     */
    fun setContextWrapper(wrapper: ArMeasureContextWrapper) {
        check(this.contextWrapper == null) { "ArMeasureConfig.setContextWrapper was already called once" }
        this.contextWrapper = wrapper
    }
}
