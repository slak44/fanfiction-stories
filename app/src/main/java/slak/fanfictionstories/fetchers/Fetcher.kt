package slak.fanfictionstories.fetchers

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import slak.fanfictionstories.utility.opt
import java.util.*

object Fetcher {
  const val RATE_LIMIT_MILLISECONDS = 300L
  const val CONNECTION_WAIT_DELAY_SECONDS = 3L
  const val CONNECTION_MISSING_DELAY_SECONDS = 5L
  const val STORAGE_WAIT_DELAY_SECONDS = 5L
  @JvmStatic
  val TAG = "Fetcher"
  @JvmStatic
  val regexOpts: Set<RegexOption> = hashSetOf(
      RegexOption.MULTILINE,
      RegexOption.UNIX_LINES,
      RegexOption.DOT_MATCHES_ALL
  )

  // Regen DB if you change this separator
  const val CHAPTER_TITLE_SEPARATOR = "^^^%!@#__PLACEHOLDER__%!@#~~~"

  private fun cleanNrMatch(res: MatchResult?): String? =
      res?.groupValues?.get(1)?.replace(",", "")?.trim()

  /**
   * Parses story metadata from the metadata div.
   */
  fun parseStoryMetadata(metadata: String): Map<String, String?> {
    val ratingLang = Regex("Rated: (?:<a .*?>Fiction[ ]+?)?(.*?)(?:</a>)? - (.*?) -", regexOpts)
        .find(metadata) ?: {
      val ex = IllegalStateException("Can't match rating/language")
      Log.e("parseStoryMetadata", "", ex)
      throw ex
    }()

    val words = Regex("Words: ([0-9,]+)", regexOpts).find(metadata) ?: {
      val ex = IllegalStateException("Can't match word count")
      Log.e("parseStoryMetadata", "", ex)
      throw ex
    }()

    val chapters = Regex("Chapters: ([0-9,]+)", regexOpts).find(metadata)
    val favs = Regex("Favs: ([0-9,]+)", regexOpts).find(metadata)
    val follows = Regex("Follows: ([0-9,]+)", regexOpts).find(metadata)
    val reviews = Regex("Reviews: (?:<a.*?>)?([0-9,]+)(?:</a>)?", regexOpts).find(metadata)

    // Disambiguate genres/characters
    val split = ArrayList(metadata.split(" - "))
    val findGenres = split.filter {
      it.contains(Regex("Adventure|Angst|Drama|Fantasy|Friendship|Humor|Hurt/Comfort|" +
          "Poetry|Romance|Sci-Fi|Supernatural|Tragedy"))
    }
    var genres = "None"
    if (findGenres.isNotEmpty()) {
      genres = findGenres[0].trim()
      split.removeAll { findGenres.contains(it) }
    }
    val thingsAfterCharacters =
        Regex("Words|Chapters|Reviews|Favs|Follows|Published|Updated", regexOpts)
    val characters = if (split[2].contains(thingsAfterCharacters)) "None" else split[2]

    return mapOf(
        "rating" to ratingLang.groupValues[1],
        "language" to ratingLang.groupValues[2],
        "words" to words.groupValues[1],
        "chapters" to cleanNrMatch(chapters),
        "favs" to cleanNrMatch(favs),
        "follows" to cleanNrMatch(follows),
        "reviews" to cleanNrMatch(reviews),
        "genres" to genres,
        "characters" to characters.trim()
    )
  }

  fun publishedTimeStoryMeta(html: String): String? {
    val time = Regex("Published: <span data-xutime='([0-9]+)'>", regexOpts).find(html)
    return if (time == null) null else time.groupValues[1]
  }

  fun updatedTimeStoryMeta(html: String): String? {
    val time = Regex("Updated: <span data-xutime='([0-9]+)'>", regexOpts).find(html)
    return if (time == null) null else time.groupValues[1]
  }

  fun authorIdFromAuthor(author: Element): Long {
    // The `href` on the author element looks like /u/6772732/Gnaoh-El-Nart, so pick the id at pos 2
    return author.attr("href").split("/")[2].toLong()
  }

  fun isComplete(metaString: String): Long = if (metaString.indexOf("Complete") > -1) 1L else 0L

  /**
   * Parses all required metadata for a [StoryModel].
   * @param html html string of any chapter of the story
   */
  fun parseMetadata(html: String, storyId: Long): MutableMap<String, Any> {
    // The raw html is completely insane
    // I mean really, using ' for attributes?
    // Sometimes not using any quotes at all?
    // Mixing lower case and upper case for tags?
    // Inline css/js?
    // Having a tag soup because line breaks appear at random?
    // Not closing tags that should have been?
    // Are the standards too permissive, or browser implementations...
    // Thank god for html parsers

    val doc = Jsoup.parse(html)

    val author = doc.select("#profile_top > a.xcontrast_txt")[0]
    val title = doc.select("#profile_top > b.xcontrast_txt")[0].text()
    val summary = doc.select("#profile_top > div.xcontrast_txt")[0].text()
    val categories = doc.select("#pre_story_links > span.lc-left > a.xcontrast_txt")
    val metaString = doc.select("#profile_top > span.xgray").html()
    val meta = parseStoryMetadata(metaString)

    val chapters = if (meta["chapters"] != null) meta["chapters"]!!.toLong() else 1L

    // Parse chapter titles only if there are any chapters to name
    val chapterTitles: Optional<String> = if (meta["chapters"] == null) {
      Optional.empty()
    } else {
      doc.select("#chap_select > option").slice(0..(chapters - 1).toInt())
          .joinToString(CHAPTER_TITLE_SEPARATOR) {
        // The actual chapter title is preceded by the chapter nr, a dot, and a space:
        it.text().replace(Regex("\\d+\\. ", regexOpts), "")
      }.opt()
    }

    val publishTime = publishedTimeStoryMeta(html)
    val updateTime = updatedTimeStoryMeta(html)

    return mutableMapOf(
        "storyId" to storyId,
        "rating" to meta["rating"]!!,
        "language" to meta["language"]!!,
        "genres" to meta["genres"]!!,
        "characters" to meta["characters"]!!,
        "chapters" to chapters,
        "wordCount" to meta["words"]!!.replace(",", "").toLong(),
        "reviews" to if (meta["reviews"] != null) meta["reviews"]!!.toLong() else 0L,
        "favorites" to if (meta["favs"] != null) meta["favs"]!!.toLong() else 0L,
        "follows" to if (meta["follows"] != null) meta["follows"]!!.toLong() else 0L,
        "publishDate" to (publishTime?.toLong() ?: 0L),
        "updateDate" to (updateTime?.toLong() ?: 0L),
        "isCompleted" to isComplete(metaString),
        "scrollProgress" to 0.0,
        "scrollAbsolute" to 0L,
        "currentChapter" to 0L,
        "status" to "remote",
        "canon" to categories[0].text(),
        "category" to categories[1].text(),
        "summary" to summary,
        "authorid" to authorIdFromAuthor(author),
        "author" to author.text(),
        "title" to title,
        "chapterTitles" to chapterTitles.orElse("")
    )
  }
  // TODO: consider a general cache for all fetchers (with different cache times obv)
}