package io.posidon.android.rsslib

class RssSource(
    var name: String,
    var url: String,
    var domain: String,
) {
    var iconUrl: String? = null
        internal set

    /**
     * If it's 0, that means there was no accent color information
     */
    var accentColor: Int? = null
        internal set
}