package com.pofuduk.core.conversation

import com.pofuduk.core.util.secondsSince
import java.time.Instant

/**
 * Merges a parsed intent with conversation context (spec: WeatherIntentResolver).
 * "Yarın hava nasıl?" → "Peki yağmur var mı?" keeps "yarın"; a new date or city replaces context.
 */
class WeatherIntentResolver {
    fun resolve(intent: WeatherIntent, context: ResolvedQuery?): ResolvedQuery {
        val inherit = intent.isFollowUp && context != null
        val date = intent.date ?: if (inherit) context!!.date else DateReference.Today
        val dayPart = intent.dayPart ?: if (inherit && intent.date == null) context!!.dayPart else null
        val province = intent.province ?: if (inherit) context!!.province else null
        return ResolvedQuery(intent.topic, date, dayPart, province)
    }
}

/** Holds the short-term conversation context. Context expires after [contextLifetimeSeconds]. */
class ConversationManager(var contextLifetimeSeconds: Double = 10.0 * 60) {
    var context: ResolvedQuery? = null
        private set
    private var contextTime: Instant? = null
    private val parser = WeatherQueryParser()
    private val resolver = WeatherIntentResolver()

    fun interpret(text: String, now: Instant): ResolvedQuery {
        val t = contextTime
        if (t != null && now.secondsSince(t) > contextLifetimeSeconds) context = null
        val query = resolver.resolve(parser.parse(text), context)
        context = query
        contextTime = now
        return query
    }

    fun reset() {
        context = null
        contextTime = null
    }
}
