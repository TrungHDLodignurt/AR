package vn.apero.armeasure.photo.data

import androidx.core.content.FileProvider
import vn.apero.armeasure.photo.R

/**
 * Module-owned [FileProvider] subclass, declared under its own authority
 * (`${applicationId}.armeasure.fileprovider`) rather than reusing the bare
 * `androidx.core.content.FileProvider` class name.
 *
 * A host app very likely declares its own `FileProvider` already; two `<provider>` entries with
 * the same `android:name` is a manifest-merger conflict. Subclassing avoids that collision.
 * Kotlin `internal` compiles to a public JVM class, so the platform can still instantiate this by
 * name from the manifest while Kotlin consumers of this module cannot reference it directly.
 */
internal class ArMeasurePhotoFileProvider : FileProvider(R.xml.armeasure_file_paths)
