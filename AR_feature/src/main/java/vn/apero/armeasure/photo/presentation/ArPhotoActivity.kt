package vn.apero.armeasure.photo.presentation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import vn.apero.armeasure.common.ui.ArMeasureTheme
import vn.apero.armeasure.photo.data.CustomReferenceStore

/**
 * Module-owned, full-screen photo-measure Activity — the grid/editor screens phases 07/08 build
 * out land inside this same Activity (in-Activity state, no NavHost, per the nested-Activities
 * navigation model).
 *
 * Constructs its own [CustomReferenceStore]. This reverses the earlier locked decision "the host
 * always constructs the store": that decision predates this module owning its own Activity — with
 * no host Activity for the module to receive a store from, there is no host left to construct it,
 * and [CustomReferenceStore] was already `internal`, so no encapsulation is actually lost.
 */
internal class ArPhotoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ArMeasureTheme(dark = false) {
                val store = remember { CustomReferenceStore(this) }
                PhotoMeasureScreen(
                    referenceStore = store,
                    modifier = Modifier.fillMaxSize(),
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, ArPhotoActivity::class.java)
        fun start(context: Context) = context.startActivity(newIntent(context))
    }
}
