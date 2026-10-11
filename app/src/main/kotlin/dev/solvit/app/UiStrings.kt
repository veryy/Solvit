package dev.solvit.app

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import org.json.JSONObject

/** Offline UI catalog. User messages, prompts, code and remote results stay verbatim. */
class UiStrings(private val entries: Map<String,Map<String,String>>) {
    private val templates by lazy {
        entries.filterKeys { "{0}" in it }.map { (source, translations) ->
            val parts = Regex("\\{(\\d+)\\}").findAll(source).toList()
            var cursor = 0
            val pattern = buildString {
                append("^")
                for (part in parts) { append(Regex.escape(source.substring(cursor,part.range.first))); append("(.*?)"); cursor=part.range.last+1 }
                append(Regex.escape(source.substring(cursor))); append("$")
            }
            Triple(Regex(pattern,RegexOption.DOT_MATCHES_ALL),parts.map { it.groupValues[1].toInt() },translations)
        }
    }
    fun text(source: String, language: String): String {
        if (language == "zh") return source
        entries[source]?.get(language)?.let { return it }
        for ((pattern,indices,translations) in templates) {
            val match = pattern.matchEntire(source) ?: continue
            val translated = translations[language] ?: return source
            val values = indices.mapIndexed { group, index -> index to match.groupValues[group+1] }.toMap()
            return Regex("\\{(\\d+)\\}").replace(translated) { values[it.groupValues[1].toInt()] ?: it.value }
        }
        return source
    }
    companion object {
        fun load(context: Context): UiStrings {
            val json = JSONObject(context.assets.open("ui-locales.json").bufferedReader().use { it.readText() })
            val entries = json.keys().asSequence().associateWith { key ->
                val row = json.getJSONObject(key)
                row.keys().asSequence().associateWith { language -> row.getString(language) }
            }
            return UiStrings(entries)
        }
    }
}
val LocalUiStrings = staticCompositionLocalOf { UiStrings(emptyMap()) }
val LocalUiLanguage = compositionLocalOf { "zh" }

@Composable
fun UiText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null, fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null, textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1, style: TextStyle = LocalTextStyle.current) {
    Text(LocalUiStrings.current.text(text,LocalUiLanguage.current),modifier=modifier,color=color,fontSize=fontSize,
        fontStyle=fontStyle,fontWeight=fontWeight,fontFamily=fontFamily,textAlign=textAlign,lineHeight=lineHeight,
        overflow=overflow,softWrap=softWrap,maxLines=maxLines,minLines=minLines,style=style)
}
@Composable
fun UiIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val strings=LocalUiStrings.current; val language=LocalUiLanguage.current
    Icon(imageVector,contentDescription?.let { strings.text(it,language) },modifier,tint)
}

/** For fixed UI fragments embedded around verbatim user-defined names. */
@Composable
fun uiString(source: String): String = LocalUiStrings.current.text(source, LocalUiLanguage.current)
