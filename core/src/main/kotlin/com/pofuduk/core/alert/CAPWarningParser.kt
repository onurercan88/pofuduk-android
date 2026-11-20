package com.pofuduk.core.alert

import com.pofuduk.core.model.OfficialWarning
import com.pofuduk.core.model.WarningLevel
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses CAP 1.2 (Common Alerting Protocol, the WMO standard for warnings) into [OfficialWarning]s.
 * Accepts a single <alert> or a document containing several. Prefers Turkish <info> blocks.
 * Level: MeteoAlarm-style `awareness_level` parameter ("2; yellow; Moderate") when present,
 * otherwise CAP severity (Moderate → yellow, Severe → orange, Extreme → red, Minor → green).
 *
 * Security: documents with a DOCTYPE are rejected (no DTDs/external entities; CAP never needs them).
 */
object CAPWarningParser {
    fun parse(data: ByteArray): List<OfficialWarning>? {
        val text = data.toString(Charsets.UTF_8)
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) return null
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(data))
        } catch (e: Exception) {
            return null
        }
        return elements(document.documentElement, "alert", deep = true).mapNotNull(::warning)
    }

    private class Info(
        val language: String, val event: String, val headline: String, val description: String,
        val severity: String, val web: String, val onset: String, val effective: String, val expires: String,
        val awareness: String, val areas: List<String>,
    )

    private fun warning(alert: Element): OfficialWarning? {
        val identifier = childText(alert, "identifier")
        val sent = childText(alert, "sent")
        val infos = elements(alert, "info").map { info ->
            val awareness = elements(info, "parameter")
                .firstOrNull { childText(it, "valueName").equals("awareness_level", ignoreCase = true) }
                ?.let { childText(it, "value") } ?: ""
            Info(
                language = childText(info, "language"), event = childText(info, "event"),
                headline = childText(info, "headline"), description = childText(info, "description"),
                severity = childText(info, "severity"), web = childText(info, "web"),
                onset = childText(info, "onset"), effective = childText(info, "effective"), expires = childText(info, "expires"),
                awareness = awareness,
                areas = elements(info, "area").map { childText(it, "areaDesc") }.filter { it.isNotEmpty() },
            )
        }
        val info = infos.firstOrNull { it.language.lowercase().startsWith("tr") } ?: infos.firstOrNull() ?: return null
        val expires = date(info.expires) ?: return null
        val from = date(info.onset) ?: date(info.effective) ?: date(sent) ?: return null
        val level = level(info.awareness, info.severity) ?: return null
        val provinces = info.areas.flatMap { a -> a.split(',').map { it.trim() } }
        return OfficialWarning(
            id = identifier.ifEmpty { UUID.randomUUID().toString() }, level = level,
            hazard = info.event.ifEmpty { info.headline },
            description = info.description.ifEmpty { info.headline },
            provinces = provinces, validFrom = from, validUntil = expires,
            sourceUrl = info.web.takeIf { it.startsWith("https://") } ?: MGMOfficialPage.WARNINGS,
        )
    }

    internal fun level(awareness: String, severity: String): WarningLevel? {
        val a = awareness.lowercase()
        return when {
            "red" in a -> WarningLevel.RED
            "orange" in a -> WarningLevel.ORANGE
            "yellow" in a -> WarningLevel.YELLOW
            "green" in a -> WarningLevel.GREEN
            else -> when (severity.lowercase()) {
                "extreme" -> WarningLevel.RED
                "severe" -> WarningLevel.ORANGE
                "moderate" -> WarningLevel.YELLOW
                "minor" -> WarningLevel.GREEN
                else -> null
            }
        }
    }

    internal fun date(s: String): Instant? {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(trimmed).toInstant() }.getOrNull()
    }

    private fun localName(node: Node): String = node.localName ?: node.nodeName.substringAfter(':')

    /** Direct children (or all descendants when [deep]) with the given local name, any namespace. */
    private fun elements(parent: Element, name: String, deep: Boolean = false): List<Element> {
        val out = mutableListOf<Element>()
        if (deep && localName(parent) == name) {
            out += parent
            return out
        }
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (localName(child) == name) out += child else if (deep) out += elements(child, name, deep = true)
        }
        return out
    }

    private fun childText(parent: Element, name: String): String =
        elements(parent, name).firstOrNull()?.textContent?.trim() ?: ""
}
