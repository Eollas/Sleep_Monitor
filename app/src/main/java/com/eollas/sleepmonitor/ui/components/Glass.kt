package com.eollas.sleepmonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eollas.sleepmonitor.ui.theme.Palette

/**
 * Surface effet « vitre dépolie » : le fond d'écran est redessiné flou derrière
 * la carte, avec un voile clair et un liseré lumineux.
 */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = Color(0x22101530),
    blur: Float = 28f,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { pos = it.positionInRoot() }
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    renderEffect = BlurEffect(blur * density / 2.5f, blur * density / 2.5f, TileMode.Clamp)
                    clip = true
                }
                .drawBehind { translate(-pos.x, -pos.y) { backdrop.draw(this) } },
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f))))
                .background(tint)
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))),
                    shape,
                ),
        )
        content()
    }
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    icon: ImageVector? = null,
) {
    Glass(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        tint = if (accent) Palette.accentDeep.copy(alpha = 0.55f) else Color(0x22101530),
        onClick = onClick,
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = Palette.text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Palette.text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        }
    }
}

@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Glass(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        tint = if (selected) Palette.accentDeep.copy(alpha = 0.65f) else Color(0x22101530),
        onClick = onClick,
    ) {
        Text(
            text,
            Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 9.dp),
            color = if (selected) Color.White else Palette.textDim,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
fun SoftSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Palette.accentDeep,
            checkedThumbColor = Color(0xFFE8E4FA),
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
            uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
            uncheckedBorderColor = Color.White.copy(alpha = 0.2f),
        ),
    )
}
