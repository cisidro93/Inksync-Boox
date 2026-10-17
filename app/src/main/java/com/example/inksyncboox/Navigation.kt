package com.example.inksyncboox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.inksyncboox.ui.main.MainScreen
import com.example.inksyncboox.ui.main.MainScreenViewModel

@Composable
fun MainNavigation(viewModel: MainScreenViewModel? = null) {
    val backStack = rememberNavBackStack(Main)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Main> {
                if (viewModel != null) {
                    MainScreen(
                        onItemClick = { navKey -> backStack.add(navKey) },
                        modifier = Modifier.fillMaxSize(),
                        viewModel = viewModel
                    )
                } else {
                    MainScreen(
                        onItemClick = { navKey -> backStack.add(navKey) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    )
}
