package slak.fanfictionstories

import io.mockk.every
import io.mockk.mockk
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.junit.Assert.assertEquals
import org.junit.Test
import slak.fanfictionstories.data.fetchers.ParserUtils

private const val publishTime = 1650095121L
private const val updateTime = 1644817521L
private const val chapters = 12L
private const val reviews = 268L
private const val favs = 17L
private const val follows = 13L
private const val words = 569005L
private const val rating = "K+"

private val canonStoryListNodes = listOf(
    """Rated: $rating - English - Adventure/Family - Chapters: $chapters - Words: 569,005 - Reviews: $reviews - Favs: $favs - Follows: $follows - Updated: """,
    """<span data-xutime="$updateTime">7h ago</span>""",
    """ - Published: """,
    """<span data-xutime="$publishTime">Feb 14</span>""",
    """ - [Jane D., John D.] - Complete"""
)

private val storyChapterNodes = listOf(
    """Rated: """,
    """<a class="xcontrast_txt" href="https://www.fictionratings.com/" target="rating">Fiction $rating</a>""",
    """ - English - Adventure/Family - Chapters: $chapters - Words: 569,005 - Reviews: """,
    """<a href="/r/14099999/">$reviews</a>""",
    """ - Favs: $favs - Follows: $follows - Updated: """,
    """<span data-xutime="$updateTime">7h ago</span>""",
    """ - Published: """,
    """<span data-xutime="$publishTime">Feb 14</span>""",
    """ - [Jane D., John D.] - Status: Complete - id: 14099999"""
)

fun assertStoryValid(fragment: StoryModelFragment) {
  assertEquals(1L, fragment.isComplete)
  assertEquals(publishTime, fragment.publishTime)
  assertEquals(updateTime, fragment.updateTime)
  assertEquals(chapters, fragment.chapterCount)
  assertEquals(reviews, fragment.reviews)
  assertEquals(favs, fragment.favorites)
  assertEquals(follows, fragment.follows)
  assertEquals(words, fragment.wordCount)
  assertEquals(rating, fragment.rating)
}

private fun mockElement(strings: List<String>): Element {
  val mockedNodes = strings.map {
    if (it.startsWith("<")) {
      mockk<Node>()
    } else {
      val node = mockk<TextNode>()
      every { node.text() } returns it
      node
    }
  }

  val element = mockk<Element>()
  every { element.html() } returns strings.joinToString("")
  every { element.childNodes() } returns mockedNodes

  return element
}

private val canonStoryListMetadataElement = mockElement(canonStoryListNodes)
private val storyChapterMetadataElement = mockElement(storyChapterNodes)

private const val authorId = 999988899L
private const val authorHref = """/u/$authorId/cooltestname123"""

class ParserTests {
  @Test
  fun `Parse story metadata from canon story list HTML`() {
    val meta = ParserUtils.parseStoryMetadata(canonStoryListMetadataElement, 0)
    assertStoryValid(meta)
  }

  @Test
  fun `Parse story metadata from story chapter HTML`() {
    val meta = ParserUtils.parseStoryMetadata(storyChapterMetadataElement, 0)
    assertStoryValid(meta)
  }

  @Test
  fun `Parse authorId from HTML successfully`() {
    val element = mockk<Element>()
    every { element.attr("href") } returns authorHref

    val parsedId = ParserUtils.authorIdFromAuthor(element)
    assertEquals(authorId, parsedId)
  }
}