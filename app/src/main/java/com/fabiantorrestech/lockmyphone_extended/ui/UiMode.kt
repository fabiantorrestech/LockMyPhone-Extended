package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.fabiantorrestech.lockmyphone_extended.data.UiMode

/** Simple or Pro, provided once by MainActivity so any screen can hide its fine-grained controls. */
val LocalUiMode = compositionLocalOf { UiMode.SIMPLE }

/** True in Pro mode: show the fine-grained controls. */
@Composable
fun isPro(): Boolean = LocalUiMode.current == UiMode.PRO
