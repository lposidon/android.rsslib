package io.posidon.android.rsslib

import org.xmlpull.v1.XmlPullParser

internal object RSSHelper {

    internal fun getSourceInfo(url: String): Triple<String, String, String> {
        val u = if (url.endsWith("/")) {
            url.substring(0, url.length - 1)
        } else url
        return if (u.startsWith("http://") || u.startsWith("https://")) {
            val slashI = u.indexOf('/', 8)
            val domain = if (slashI != -1) u.substring(0, slashI) else u
            Triple(
                u, domain, if (domain.startsWith("www.")) {
                    domain.substring(4)
                } else domain
            )
        } else {
            val slashI = u.indexOf('/')
            val domain = if (slashI != -1) u.substring(0, slashI) else u
            Triple(
                "https://$u", "https://$domain", if (domain.startsWith("www.")) {
                    domain.substring(4)
                } else domain
            )
        }
    }

    internal fun unescapeCharacters(title: String): String {
        return buildString {
            var i = 0
            val l = title.length
            while (i < l) {
                if (title[i] == '&') {
                    i++
                    if (title[i] == '#') {
                        i++
                        var u = 0
                        while (i < l && title[i] != ';') {
                            val d = title[i].digitToIntOrNull() ?: break
                            u = u * 10 + d
                            i++
                        }
                        append(u.toChar())
                    }
                } else {
                    append(title[i])
                }
                i++
            }
        }
    }

    internal fun parseInside(parser: XmlPullParser, parentTag: String, childTag: String): String? {
        loop@ while (parser.next() != XmlPullParser.END_DOCUMENT) {
            val name = parser.name ?: continue
            when (parser.eventType) {
                XmlPullParser.END_TAG -> {
                    if (name == parentTag) return null
                }
                XmlPullParser.START_TAG -> {
                    if (name == childTag) return getText(parser)
                }
            }
        }
        return null
    }

    internal inline fun getText(parser: XmlPullParser): String {
        return if (parser.next() == XmlPullParser.TEXT) {
            parser.text.also { parser.nextTag() }
        } else ""
    }
}