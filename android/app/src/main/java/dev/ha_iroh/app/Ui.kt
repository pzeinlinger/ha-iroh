package dev.ha_iroh.app

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Material You colors (minSdk 31), following the system dark mode. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx), content = content)
}

/** Back arrow that behaves like the system back (runs the screen's BackHandler, or leaves the activity). */
@Composable
fun BackArrow() {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    IconButton({ dispatcher?.onBackPressed() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) =
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier)

/** Text for the user: uniffi exceptions often carry an empty message, so fall back to the class name. */
fun Throwable.userMessage() = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
