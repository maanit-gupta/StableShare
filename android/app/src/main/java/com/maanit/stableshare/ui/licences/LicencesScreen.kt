package com.maanit.stableshare.ui.licences

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maanit.stableshare.R
import com.maanit.stableshare.ui.components.neutralCard
import com.maanit.stableshare.ui.theme.Neutral

/** The bundled fonts' OFL texts, read from assets/licenses. */
private val licences = listOf(R.string.licence_comfortaa to "licenses/comfortaa_OFL.txt", R.string.licence_inter to "licenses/inter_OFL.txt")

/** Open-source licences: a simple Neutral screen (approved Phase 4 addition to UI-SPEC §5.10). */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val texts = remember { licences.map { (name, path) -> name to assets.open(path).bufferedReader().use { it.readText() } } }
    Column(
        Modifier
            .fillMaxSize()
            .background(Neutral.colors.page)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = Neutral.colors.inkPrimary, modifier = Modifier.size(24.dp))
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.licences_title), style = Neutral.type.title)
            texts.forEach { (name, body) ->
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth().neutralCard().padding(16.dp)) {
                    Text(stringResource(name), style = Neutral.type.heading)
                    Spacer(Modifier.height(8.dp))
                    Text(body.trim(), style = Neutral.type.body)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
