package io.posidon.android.rsslib

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.concurrent.thread
import kotlin.math.max

@Suppress("MemberVisibilityCanBePrivate")
object RSS {

    val COMMON_URL_SUFFIXES = arrayOf("",
        "/feed",
        "/feed.xml",
        "/rss",
        "/rss.xml",
        "/atom",
        "/atom.xml",
    )

    /**
     * Loads the rss content asynchronously
     *
     * @param urls            A list of all the rss/atom feeds to load
     * @param maxItemsPerURL  Maximum amount of items to load for each URL (if 0, no limit is applied)
     * @param filter          A function to decide whether to include an item or not
     * @param onFinished      The function to handle the loaded data (unsorted)
     *
     * @return the loading thread
     */
    inline fun load(
        vararg urls: String,
        maxItemsPerURL: Int = 0,
        noinline filter: (url: String, title: String, time: Date) -> Boolean = { _, _, _ -> true },
        crossinline onFinished: (errorURLs: List<RssSource>, items: List<RssItem>) -> Unit,
    ): Thread = thread(name = "RSS loading thread", isDaemon = true) {
        val feedItems = ArrayList<RssItem>()
        onFinished(load(feedItems, urls.asIterable(), maxItemsPerURL, filter), feedItems)
    }

    /**
     * Loads the rss content asynchronously
     *
     * @param urls            A list of all the rss/atom feeds to load
     * @param maxItemsPerURL  Maximum amount of items to load for each URL (if 0, no limit is applied)
     * @param filter          A function to decide whether to include an item or not
     * @param onFinished      The function to handle the loaded data (unsorted)
     *
     * @return the loading thread
     */
    inline fun load(
        urls: Iterable<String>,
        maxItemsPerURL: Int = 0,
        noinline filter: (url: String, title: String, time: Date) -> Boolean = { _, _, _ -> true },
        crossinline onFinished: (errorURLs: List<RssSource>, items: List<RssItem>) -> Unit,
    ): Thread = thread(name = "RSS loading thread", isDaemon = true) {
        val feedItems = ArrayList<RssItem>()
        onFinished(load(feedItems, urls, maxItemsPerURL, filter), feedItems)
    }

    /**
     * Loads the rss content on the current thread
     *
     * @param output          The list to write the rss data to (unsorted)
     * @param urls            A list of all the rss/atom feeds to load
     * @param maxItemsPerURL  Maximum amount of items to load for each URL (if 0, no limit is applied)
     * @param filter          A function to decide whether to include an item or not
     *
     * @return A list of URLs that failed
     */
    fun load(
        output: MutableList<RssItem>,
        urls: Iterable<String>,
        maxItemsPerURL: Int = 0,
        filter: (url: String, title: String, time: Date) -> Boolean = { _, _, _ -> true },
    ): LinkedList<RssSource> {
        val lock = ReentrantLock()

        val erroredSources = LinkedList<RssSource>()
        val threads = LinkedList<Thread>()
        for (u in urls) {
            if (u.isNotEmpty()) {
                val (url, domain, name) = RSSHelper.getSourceInfo(u)
                threads.add(thread(name = "RSS internal thread", isDaemon = true) {
                    var i = 0
                    while (i < COMMON_URL_SUFFIXES.size) {
                        try {
                            val newUrl = url + COMMON_URL_SUFFIXES[i]
                            val connection = URL(newUrl).openConnection()
                            parseFeed(
                                output,
                                connection.getInputStream(),
                                RssSource(name, newUrl, domain),
                                lock,
                                filter,
                                maxItemsPerURL,
                            )
                            i = -1
                            break
                        }
                        catch (e: IOException) {}
                        i++
                    }
                    if (i != -1) {
                        erroredSources.add(RssSource(name, url, domain))
                    }
                })
            }
        }

        val m = System.currentTimeMillis()
        for (thread in threads) {
            val millis = System.currentTimeMillis() - m
            kotlin.runCatching {
                thread.join(max(60000 - millis, 0))
            }
        }

        return erroredSources
    }

    private val pullParserFactory: XmlPullParserFactory = XmlPullParserFactory.newInstance()

    @Throws(XmlPullParserException::class, IOException::class)
    private inline fun parseFeed(
        output: MutableList<in RssItem>,
        inputStream: InputStream,
        source: RssSource,
        lock: ReentrantLock,
        filter: (url: String, title: String, time: Date) -> Boolean,
        maxItemsPerURL: Int,
    ) {
        var title: String? = null
        var link: String? = null
        var img: String? = null
        var time: Date? = null
        var id: String? = null
        var isPermaLink = false
        var isItem = 0
        val items = ArrayList<RssItem>()
        inputStream.use {
            val parser: XmlPullParser = pullParserFactory.newPullParser()
            parser.setInput(inputStream, null)
            parser.nextTag()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                val name = parser.name ?: continue
                when (parser.eventType) {
                    XmlPullParser.END_TAG -> when {
                        name.equals("item", ignoreCase = true) ||
                        name.equals("entry", ignoreCase = true) -> {
                            isItem = 0
                            if (link == null && isPermaLink) {
                                link = id
                            }
                            if (title != null && link != null) {
                                if (filter(link!!, title!!, time!!)) {
                                    val t = RSSHelper.unescapeCharacters(title!!)
                                    items.add(RssItem(t, link!!, img, time!!, source))
                                    if (maxItemsPerURL != 0 && items.size >= maxItemsPerURL) {
                                        return@use
                                    }
                                }
                            }
                            title = null
                            link = null
                            img = null
                            time = null
                            id = null
                            isPermaLink = false
                        }
                    }
                    XmlPullParser.START_TAG -> when {
                        name.equals("item", ignoreCase = true) -> isItem = 1
                        name.equals("entry", ignoreCase = true) -> isItem = 2
                        isItem == 1 -> when { //RSS
                            name.equals("title", ignoreCase = true) -> title = RSSHelper.getText(parser)
                            name.equals("guid", ignoreCase = true) -> {
                                id = RSSHelper.getText(parser)
                                isPermaLink = parser.getAttributeValue(null, "isPermaLink").toBoolean()
                            }
                            name.equals("link", ignoreCase = true) && link == null -> link = RSSHelper.getText(parser)
                            name.equals("pubDate", ignoreCase = true) -> {
                                val text = RSSHelper.getText(parser)
                                    .replace("GMT", "+0000")
                                    .replace("EDT", "+0000")
                                    .trim()
                                time = try {
                                    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(text)!!
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(text)!! }
                                    catch (e: Exception) {
                                        e.printStackTrace()
                                        Date(0)
                                    }
                                }
                            }
                            img == null -> when (name) {
                                "description", "content:encoded" -> {
                                    val result = RSSHelper.getText(parser)
                                    val i = result.indexOf("src=\"", result.indexOf("img"))
                                    if (i != -1) {
                                        val end = result.indexOf("\"", i + 5)
                                        img = result.substring(i + 5, end)
                                    }
                                }
                                "image" -> img = RSSHelper.getText(parser)
                                "media:content" -> {
                                    val medium = parser.getAttributeValue(null, "medium")
                                    val url = parser.getAttributeValue(null, "url")
                                    if (medium == "image" ||
                                        url.endsWith(".jpg") ||
                                        url.endsWith(".png") ||
                                        url.endsWith(".svg") ||
                                        url.endsWith(".jpeg")) {
                                        img = url
                                    }
                                }
                                "media:thumbnail", "enclosure" -> img = parser.getAttributeValue(null, "url")
                                "itunes:image" -> img = parser.getAttributeValue(null, "href")
                            }
                        }
                        isItem == 2 -> when { //Atom
                            name.equals("title", ignoreCase = true) -> title = RSSHelper.getText(parser)
                            name.equals("id", ignoreCase = true) -> link = RSSHelper.getText(parser)
                            name.equals("published", ignoreCase = true) ||
                            name.equals("updated", ignoreCase = true) -> {
                                val text = RSSHelper.getText(parser).trim()
                                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                                time = try { format.parse(text)!! } catch (e: Exception) { Date(0) }
                            }
                            img == null && (name.equals("summary", ignoreCase = true) || name.equals("content", ignoreCase = true)) -> {
                                val result = RSSHelper.getText(parser)
                                val i = result.indexOf("src=\"", result.indexOf("img"))
                                if (i != -1) {
                                    val end = result.indexOf("\"", i + 5)
                                    img = result.substring(i + 5, end)
                                }
                            }
                        }
                        name.equals("title", ignoreCase = true) -> {
                            val new = RSSHelper.getText(parser)
                            if (new.isNotBlank()) {
                                source.name = new
                            }
                        }
                        name.equals("icon", ignoreCase = true) -> {
                            val new = RSSHelper.getText(parser)
                            if (new.isNotBlank()) {
                                source.iconUrl = new
                            }
                        }
                        name.equals("image", ignoreCase = true) -> {
                            val new = RSSHelper.parseInside(parser, name, "url")
                            if (!new.isNullOrBlank()) {
                                source.iconUrl = new
                            }
                        }
                        name.equals("webfeeds:icon", ignoreCase = true) -> {
                            val new = RSSHelper.getText(parser)
                            if (new.isNotBlank()) {
                                source.iconUrl = new
                            }
                        }
                        name.equals("webfeeds:accentColor", ignoreCase = true) -> {
                            val new = RSSHelper.getText(parser)
                            if (new.isNotBlank()) {
                                val color = new.toInt(16)
                                source.accentColor = color or 0xff000000.toInt()
                            }
                        }
                    }
                }
            }
        }
        lock.lock()
        output.addAll(items)
        lock.unlock()
    }
}