package com.prediction.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

val lightAppHeader = "#08081A"
val lightAppBackground = "#1A1A33"   // lighter background

val barColor = lightAppHeader // cooler, with more blue
val lightEditFields = "#707171"
// Slightly lighter blue-black

val lightAppFrontGround = "#1A1A2E"
val lightActive = "#388E3C"
val lightInactive = "#D32F2F"
val lightWarning = "#D3D3D3"
val lightErrorColor = "#FF0000"
val lightTextColor = "#FFFFFF"
val lightTheIconColor = lightAppHeader
val lightActionColor = lightTheIconColor
val lightMainHeadingColor = lightTheIconColor
val DangerRed = "#FF1744"      // AVERAGE
val NeutralGray = "#B0BEC5"    // GOOD
val SuccessGreen = "#009624"   // EXCELLENT
val IndicatorBlue = "#0D47A1"
var AppHeader by mutableStateOf(lightAppHeader)
    private set
var EditFields by mutableStateOf(lightEditFields)
    private set
var AppBackground by mutableStateOf(lightAppBackground)
    private set
var AppFrontGround by mutableStateOf(lightAppFrontGround)
    private set
var Active by mutableStateOf(lightActive)
    private set
var InActive by mutableStateOf(lightInactive)
    private set
var Warning by mutableStateOf(lightWarning)
    private set
var ErrorColor by mutableStateOf(lightErrorColor)
    private set
var TextColor by mutableStateOf(lightTextColor)
    private set
var theIconColor by mutableStateOf(lightTheIconColor)
    private set
var actionColor by mutableStateOf(lightActionColor)
    private set
var MainHeadingColor by mutableStateOf(lightMainHeadingColor)
    private set

