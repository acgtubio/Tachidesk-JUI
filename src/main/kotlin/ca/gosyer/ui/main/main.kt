/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ca.gosyer.ui.main

import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.awaitApplication
import androidx.compose.ui.window.rememberWindowState
import ca.gosyer.BuildConfig
import ca.gosyer.core.logging.initializeLogger
import ca.gosyer.data.DataModule
import ca.gosyer.data.server.ServerService
import ca.gosyer.data.server.ServerService.ServerResult
import ca.gosyer.data.translation.XmlResourceBundle
import ca.gosyer.data.ui.UiPreferences
import ca.gosyer.data.ui.model.ThemeMode
import ca.gosyer.ui.base.WindowDialog
import ca.gosyer.ui.base.components.LoadingScreen
import ca.gosyer.ui.base.prefs.asStateIn
import ca.gosyer.ui.base.resources.LocalResources
import ca.gosyer.ui.base.resources.stringResource
import ca.gosyer.ui.base.theme.AppTheme
import ca.gosyer.util.lang.withUIContext
import ca.gosyer.util.system.getAsFlow
import ca.gosyer.util.system.userDataDir
import com.github.weisj.darklaf.LafManager
import com.github.weisj.darklaf.theme.DarculaTheme
import com.github.weisj.darklaf.theme.IntelliJTheme
import com.github.zsoltk.compose.backpress.BackPressHandler
import com.github.zsoltk.compose.backpress.LocalBackPressHandler
import com.github.zsoltk.compose.savedinstancestate.Bundle
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.launchIn
import toothpick.configuration.Configuration
import toothpick.ktp.KTP
import toothpick.ktp.extension.getInstance
import java.io.File
import kotlin.system.exitProcess

@OptIn(DelicateCoroutinesApi::class)
suspend fun main() {
    initializeLogger(File(userDataDir, "logging"))

    if (BuildConfig.DEBUG) {
        System.setProperty("kotlinx.coroutines.debug", "on")
    }

    KTP.setConfiguration(
        if (BuildConfig.DEBUG) {
            Configuration.forDevelopment()
        } else {
            Configuration.forProduction()
        }
    )

    val scope = KTP.openRootScope()
        .installModules(
            DataModule
        )

    val serverService = scope.getInstance<ServerService>()
    val uiPreferences = scope.getInstance<UiPreferences>()
    val resources = scope.getInstance<XmlResourceBundle>()

    uiPreferences.themeMode()
        .getAsFlow {
            if (!uiPreferences.windowDecorations().get() || System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64") {
                return@getAsFlow
            }
            val theme = when (it) {
                ThemeMode.Light -> IntelliJTheme()
                ThemeMode.Dark -> DarculaTheme()
            }
            withUIContext {
                LafManager.install(theme)
            }
        }
        .launchIn(GlobalScope)

    val windowSettings = uiPreferences.window()
    val (
        position,
        size,
        placement
    ) = windowSettings.get().get()

    val confirmExit = uiPreferences.confirmExit().asStateIn(GlobalScope)

    awaitApplication {
        // Exit the whole application when this window closes
        DisposableEffect(Unit) {
            onDispose {
                exitProcess(0)
            }
        }

        val backPressHandler = remember { BackPressHandler() }

        val rootBundle = remember { Bundle() }
        val windowState = rememberWindowState(
            size = size,
            position = position,
            placement = placement
        )

        Window(
            onCloseRequest = {
                if (confirmExit.value) {
                    WindowDialog(
                        title = resources.getStringA("confirm_exit"),
                        onPositiveButton = ::exitApplication
                    ) {
                        Text(stringResource("confirm_exit_message"))
                    }
                } else {
                    exitApplication()
                }
            },
            title = BuildConfig.NAME,
            state = windowState,
            onKeyEvent = {
                when (it.key) {
                    Key.Home -> {
                        backPressHandler.handle()
                    }
                    else -> false
                }
            }
        ) {
            AppTheme {
                CompositionLocalProvider(
                    LocalBackPressHandler provides backPressHandler,
                    LocalResources provides resources
                ) {
                    val initialized by serverService.initialized.collectAsState()
                    when (initialized) {
                        ServerResult.STARTED, ServerResult.UNUSED -> {
                            MainMenu(rootBundle)
                        }
                        ServerResult.STARTING, ServerResult.FAILED -> {
                            Surface {
                                LoadingScreen(
                                    initialized == ServerResult.STARTING,
                                    errorMessage = stringResource("unable_to_start_server"),
                                    retryMessage = stringResource("action_start_anyway"),
                                    retry = serverService::startAnyway
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
