package com.maanit.stableshare.ui.nav

/** Navigation destinations (UI-SPEC §5). Detail and Upload are full-screen, without the bottom bar. */
object Routes {
    const val ENTRY = "entry"
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val TRANSFERS = "transfers"
    const val HISTORY = "history"
    const val SETTINGS = "settings?section={section}"
    const val DETAIL = "detail/{id}"
    const val UPLOAD = "upload"
    const val LICENCES = "licences"

    const val SECTION_SERVER = "server"
    const val SECTION_TRANSFERS = "transfers"

    fun settings(section: String? = null) = if (section == null) "settings" else "settings?section=$section"
    fun detail(id: String) = "detail/$id"

    /** Routes that show the bottom navigation bar. */
    val withBottomBar = setOf(TRANSFERS, HISTORY, SETTINGS)
}
