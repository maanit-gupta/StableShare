package com.maanit.stableshare.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.maanit.stableshare.ui.mascot.MascotSheet
import com.maanit.stableshare.ui.theme.StableShareTheme

/**
 * Debug builds only: renders every mascot mood for a side-by-side check against
 * docs/design/mascot/previews. Start with
 * `adb shell am start -n com.maanit.stableshare/.debug.MascotGalleryActivity`.
 */
class MascotGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StableShareTheme { MascotSheet() } }
    }
}
