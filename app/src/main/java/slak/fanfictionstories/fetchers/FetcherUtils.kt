package slak.fanfictionstories.fetchers

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import slak.fanfictionstories.*
import slak.fanfictionstories.utility.str
import java.util.*

/** Utility functions common to everyone in the [slak.fanfictionstories.fetchers] package. */
object FetcherUtils {
  private val regexOpts: Set<RegexOption> = hashSetOf(
      RegexOption.MULTILINE,
      RegexOption.UNIX_LINES,
      RegexOption.DOT_MATCHES_ALL
  )

  /**
   * Separates chapter titles when stored in the database. Regen the database if you change this
   * separator.
   */
  const val CHAPTER_TITLE_SEPARATOR = "^^^%!@#__PLACEHOLDER__%!@#~~~"

  private fun cleanNrMatch(res: MatchResult?): String? =
      res?.groupValues?.get(1)?.replace(",", "")?.trim()

  /** Removes html entities, and replaces some backslash escapes. */
  fun unescape(text: String): String {
    return Parser.unescapeEntities(text, false)
        .replace("\\'", "'")
        .replace("\\\"", "\"")
  }

  /** Parses just the story metadata from the metadata div text. */
  fun parseStoryMetadata(metadata: String, element: Element): StoryModelFragment {
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
    var characters = if (split[2].contains(thingsAfterCharacters)) "None" else split[2]

    val lastNode = element.childNodes().last()
    if (lastNode is TextNode) {
      val stripStuff = lastNode.text()
          // This text is in lists of stories
          .replace(" - Complete", "")
          // These texts are in the story view itself
          .replace(" - Status: Complete", "")
          .replace(Regex(" - id: \\d+ "), "")
      if (stripStuff.isNotBlank()) {
        characters = stripStuff.trimStart(' ', '-')
      }
    }

    val publishTime = Regex("Published:[\\s]+<span data-xutime=['\"]([0-9]+)['\"]>", regexOpts)
        .find(metadata)?.groupValues?.get(1)?.toLong() ?: 0L
    val updateTime = Regex("Updated:[\\s]+<span data-xutime=['\"]([0-9]+)['\"]>", regexOpts)
        .find(metadata)?.groupValues?.get(1)?.toLong() ?: 0L

    return StoryModelFragment(
        rating = ratingLang.groupValues[1],
        language = ratingLang.groupValues[2],
        wordCount = words.groupValues[1].replace(",", "").toLong(),
        chapterCount = cleanNrMatch(chapters)?.toLong() ?: 1L,
        favorites = cleanNrMatch(favs)?.toLong() ?: 0L,
        follows = cleanNrMatch(follows)?.toLong() ?: 0L,
        reviews = cleanNrMatch(reviews)?.toLong() ?: 0L,
        genres = genres,
        characters = characters.trim(),
        publishTime = publishTime,
        updateTime = updateTime,
        isComplete = if (metadata.indexOf("Complete") > -1) 1L else 0L
    )
  }

  /**
   * Extract the author id from an element whose href points at an author page.
   *
   * The `href` on the author element looks like /u/12341234/I-Am-An-Author
   */
  fun authorIdFromAuthor(author: Element): Long {
    // Pick the id at pos 2
    return author.attr("href").split("/")[2].toLong()
  }

  /**
   * Parses all required metadata for a [StoryModel].
   * @param html html string of any chapter of the story
   */
  fun parseStoryModel(html: String, storyId: StoryId): StoryModel {
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

    val navLinks = doc.select("#pre_story_links > span.lc-left > a.xcontrast_txt")
    val canon = unescape(navLinks.last().text())
    val category =
        if (navLinks.size == 1) str(R.string.crossovers)
        else unescape(navLinks.dropLast(1).last().text())

    val metaElem = doc.select("#profile_top > span.xgray")[0]
    val meta = parseStoryMetadata(metaElem.html(), metaElem)

    // Parse chapter titles only if there are any chapters to name
    val chapterTitles: String? = if (meta.chapterCount == 1L) {
      ""
    } else {
      doc.select("#chap_select > option").slice(0..(meta.chapterCount - 1).toInt())
          .joinToString(CHAPTER_TITLE_SEPARATOR) {
            // The actual chapter title is preceded by the chapter nr, a dot, and a space:
            it.text().replace(Regex("\\d+\\. ", regexOpts), "")
          }
    }

    return StoryModel(
        storyId = storyId,
        fragment = meta,
        progress = StoryProgress(),
        status = StoryStatus.REMOTE,
        canon = canon,
        category = category,
        summary = summary,
        author = author.text(),
        authorId = authorIdFromAuthor(author),
        title = title,
        serializedChapterTitles = chapterTitles,
        addedTime = System.currentTimeMillis(),
        lastReadTime = 0
    )
  }

  /**
   * Navigation elements are common to multiple components. This function extracts the page count
   * from such an element.
   */
  fun getPageCountFromNav(nav: Element): Int {
    val navLinks = nav.children().filter {
      !it.text().contains(Regex("Next|Prev"))
    }

    return when {
      navLinks.isEmpty() -> 0 // No nav, no pages
      navLinks.last().`is`("a") ->
        // For reviews: /r/9156000/0/245/, we want the page nr, which is the last nr
        // For story lists: /game/Mass-Effect/?&srt=1&r=103&p=2, page nr is last nr as well
        navLinks.last().attr("href").trim('/').split("/", "p=").last().toInt()
      else ->
        // If it's not a link, we are on the last page and the nr is the text
        navLinks.last().text().toInt()
    }
  }
}
