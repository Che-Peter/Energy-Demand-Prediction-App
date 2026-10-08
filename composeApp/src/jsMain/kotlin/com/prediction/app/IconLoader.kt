package com.prediction.app

import androidx.compose.runtime.Composable
import com.prediction.app.ui.theme.theIconColor
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.backgroundColor
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
@Composable
fun IconForPanel(
    iconName: String,
    sizePx: Int = 24,
    tint: String = "#FFFFFF",
    altText: String = "icon",
) {
    val src = "icons/$iconName.png"
    Div({
        style {
            width(sizePx.px)
            height(sizePx.px)

            // Tint color
            backgroundColor(Color(tint))

            // Explicit mask properties (cross-browser)
            property("mask-image", "url($src)")
            property("mask-repeat", "no-repeat")
            property("mask-position", "center")
            property("mask-size", "contain")

            property("-webkit-mask-image", "url($src)")
            property("-webkit-mask-repeat", "no-repeat")
            property("-webkit-mask-position", "center")
            property("-webkit-mask-size", "contain")
        }
        attr("title", altText)
    })
}
