package slak.fanfictionstories.data.fetchers

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import slak.fanfictionstories.*
import slak.fanfictionstories.utility.str

/** Common parsing utilities for ffnet data fetchers. */
object ParserUtils {
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

  /**
   * Parses just the story metadata from the metadata div text.
   * @param element the [Element] that contains the metadata HTML.
   * @param stripLeading remove this many leading items delimited by '-'
   */
  fun parseStoryMetadata(element: Element, stripLeading: Int): StoryModelFragment {
    val metadata: String = element.html()
    val ratingLang = Regex("Rated: (?:<a .*?>Fiction[ ]+?)?(.*?)(?:</a>)? - (.*?) -", regexOpts)
        .find(metadata)
        .let { checkNotNull(it) { "Can't match rating/language" } }

    val words = Regex("Words: ([0-9,]+)", regexOpts)
        .find(metadata)
        .let { checkNotNull(it) { "Can't match word count" } }

    val chapters = Regex("Chapters: ([0-9,]+)", regexOpts).find(metadata)
    val favs = Regex("Favs: ([0-9,]+)", regexOpts).find(metadata)
    val follows = Regex("Follows: ([0-9,]+)", regexOpts).find(metadata)
    val reviews = Regex("Reviews: (?:<a.*?>)?([0-9,]+)(?:</a>)?", regexOpts).find(metadata)

    // Disambiguate genres/characters
    val split = metadata.split(" - ").let { it.slice(stripLeading until it.size) }.toMutableList()
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
   * Gets the largest image size, from the image urls found around the pages.
   *
   * If passed null, returns the default image of ffnet.
   */
  fun convertImageUrl(imageUrl: String?): String {
    if (imageUrl.isNullOrBlank()) return "/static/images/d_60_90.jpg"
    // Strip the last URL segment, like 75 below
    // https://www.fanfiction.net/image/5816992/75/
    // And add 180 instead, the currently largest known image size they serve
    return imageUrl.dropLast(1).trimEnd { it.isDigit() } + "180"
  }

  /**
   * Parses all required metadata for a [StoryModel].
   * @param html html string of any chapter of the story
   */
  suspend fun parseStoryModel(html: String, storyId: StoryId, maybeExistingModel: StoryModel? = null): StoryModel {
    val doc = Jsoup.parse(html)

    return if (doc.selectFirst("#profile_top") != null) {
      parseStoryModelDesktop(doc, storyId)
    } else {
      val model = if (maybeExistingModel != null) {
        maybeExistingModel
      } else {
        val authorElement = doc.selectFirst("#content > div > a")!!
        val author = getAuthor(authorIdFromAuthor(authorElement))
        requireNotNull(author).userStories.first { it.storyId == storyId }
      }
      val titles = (0 until model.fragment.chapterCount).joinToString(CHAPTER_TITLE_SEPARATOR) {
        "Chapter ${it + 1}"
      }
      model.copy(
          serializedChapterTitles = titles,
          status = StoryStatus.REMOTE,
          addedTime = System.currentTimeMillis(),
          lastReadTime = 0,
      )
    }
  }

  private fun parseStoryModelMobile(doc: Document, storyId: StoryId): StoryModel {
    // FIXME
    val author = doc.selectFirst("#content > a")!!
    val title = doc.selectFirst("#content > b")!!.text()

    val navLinks = doc.select("#content > a")
    val canon = unescape(navLinks.last()!!.text())
    val category =
        if (navLinks.size == 1) str(R.string.crossovers)
        else unescape(navLinks.dropLast(1).last().text())

    val meta = parseStoryMetadata(doc.selectFirst("#profile_top > span.xgray")!!, 0)

    // Parse chapter titles only if there are any chapters to name
    val chapterTitles: String = if (meta.chapterCount == 1L) {
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
        summary = "",
        author = author.text(),
        authorId = authorIdFromAuthor(author),
        title = title,
        serializedChapterTitles = chapterTitles,
        addedTime = System.currentTimeMillis(),
        lastReadTime = 0,
        imageUrl = ""
    )
  }

  private fun parseStoryModelDesktop(doc: Document, storyId: StoryId): StoryModel {
    // The raw html is completely insane
    // - Using ' for attributes
    // - Sometimes not using any quotes at all
    // - Mixing lower case and upper case for tags
    // - Inline css/js, somewhat at random
    // - Tag soup because line breaks appear at random
    // - Not closing opened tags
    // - document.write in 2022
    // Thank god for html parsers

    val author = doc.selectFirst("#profile_top > a.xcontrast_txt")!!
    val title = doc.selectFirst("#profile_top > b.xcontrast_txt")!!.text()
    val summary = doc.selectFirst("#profile_top > div.xcontrast_txt")!!.text()

    val imgUrl = convertImageUrl(doc.selectFirst("#profile_top > span > img.cimage")?.attr("src"))

    val navLinks = doc.select("#pre_story_links > span.lc-left > a.xcontrast_txt")
    val canon = unescape(navLinks.last()!!.text())
    val category =
        if (navLinks.size == 1) str(R.string.crossovers)
        else unescape(navLinks.dropLast(1).last().text())

    val meta = parseStoryMetadata(doc.selectFirst("#profile_top > span.xgray")!!, 0)

    // Parse chapter titles only if there are any chapters to name
    val chapterTitles: String = if (meta.chapterCount == 1L) {
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
        lastReadTime = 0,
        imageUrl = imgUrl
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
