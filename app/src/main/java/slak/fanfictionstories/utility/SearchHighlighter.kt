package slak.fanfictionstories.utility

import android.os.Bundle
import android.support.annotation.AnyThread
import android.support.annotation.UiThread
import android.support.constraint.ConstraintLayout
import android.support.v4.app.Fragment
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import kotlinx.android.synthetic.main.fragment_search_ui.view.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.R

/** A class that implements this interface can be searched using [SearchHighlighter]. */
interface Searchable {
  /** This property provides the raw text to search into. */
  val text: String

  /** Set the new text that may include the search highlights as spans. */
  @AnyThread
  suspend fun setText(s: CharSequence)

  /** Scroll to the specified [Area] within the text element. */
  fun scrollTo(area: Area)
}

data class Area(val startPosition: Int, val length: Int) {
  val endPosition: Int get() = startPosition + length
}

/** This class stores search state and provides logic for interacting with it. */
class SearchHighlighter : Fragment() {
  private var currentHighlight: Optional<BackgroundColorSpan> = Empty()
  private var currentMatch: Int = 0
  private val matches: MutableList<Area> = mutableListOf()

  private lateinit var searchLayout: ConstraintLayout
  private lateinit var searchable: Searchable
  private lateinit var spannableText: SpannableString

  fun setSearchable(s: Searchable) {
    searchable = s
    spannableText = SpannableString(searchable.text)
  }

  companion object {
    private const val TAG = "SearchHighlighter"
    private const val RESTORE_LAYOUT_VISIBILITY = "search_is_visible"
  }

  init {
    retainInstance = true
  }

  override fun onCreateView(inflater: LayoutInflater,
                            container: ViewGroup?, savedInstanceState: Bundle?): View? {
    searchLayout = inflater.inflate(
        R.layout.fragment_search_ui, container, false) as ConstraintLayout
    Log.v(TAG, "Search UI inflated")
    searchLayout.searchNextMatchBtn.setOnClickListener { highlightNextMatch() }
    searchLayout.searchPrevMatchBtn.setOnClickListener { highlightPrevMatch() }
    searchLayout.searchCloseBtn.setOnClickListener {
      searchLayout.visibility = View.GONE
      spannableText = SpannableString(searchable.text)
      commitSpans()
      Static.imm.hideSoftInputFromWindow(searchLayout.windowToken, 0)
    }
    return searchLayout
  }

  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)

  }

  override fun onViewStateRestored(savedInstanceState: Bundle?) {
    super.onViewStateRestored(savedInstanceState)
    if (savedInstanceState != null) {
      searchLayout.visibility = savedInstanceState.getInt(RESTORE_LAYOUT_VISIBILITY, View.GONE)
      if (searchLayout.visibility == View.VISIBLE && matches.size > 0) {
        searchable.scrollTo(matches[currentMatch])
      }
    }
    searchLayout.editorSearch.setOnEditorActionListener { _, actionId, _ ->
      if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
      currentMatch = 0
      searchAndHighlight()
      return@setOnEditorActionListener true
    }
    searchLayout.editorSearch.addTextChangedListener(object : TextWatcher {
      override fun afterTextChanged(s: Editable) {}
      override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        // Searching for stuff less than ~3 chars long gets so many matches that we lag hard
        // If the user wants to search for something like that, he can press search manually
        if (s.length < 3) {
          // If characters were removed, we're going from 3+ letters to less than that
          // And we'd like to not have existing highlights loiter around, so clear them
          if (before > count) clear()
          return
        }
        currentMatch = 0
        searchAndHighlight()
      }
    })
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt(RESTORE_LAYOUT_VISIBILITY, searchLayout.visibility)
  }

  private fun commitSpans() = launch(CommonPool) {
    searchable.setText(spannableText)
  }

  private fun searchAndHighlight() {
    clear()
    val text = searchable.text
    val toFind = searchLayout.editorSearch.text.toString()
    // Empty string means there are no results
    if (toFind.isEmpty()) {
      searchLayout.editorSearchMatches.text = ""
      return
    }
    var startIdx = text.indexOf(toFind)
    while (startIdx != -1) {
      val p = Area(startIdx, toFind.length)
      matches.add(p)
      startIdx = text.indexOf(toFind, p.endPosition)
    }
    highlightMatches()
    if (matches.size != 0) {
      searchLayout.editorSearchMatches.text =
          str(R.string.found_x_matches_current_y, matches.size, currentMatch + 1)
      searchable.scrollTo(matches[currentMatch])
    } else {
      searchLayout.editorSearchMatches.text = str(R.string.no_matches_found)
    }
  }

  /** Make the searching UI visible, and bring it into focus. */
  fun show() {
    searchLayout.visibility = View.VISIBLE
    searchLayout.editorSearch.requestFocus()
    highlightMatches()
  }

  /**
   * Highlights the next matching [Area] in the text. Wraps to the beginning after the last match.
   */
  fun highlightNextMatch() {
    if (matches.size == 0) return
    if (currentMatch + 1 != matches.size) currentMatch++
    else currentMatch = 0
    highlightCurrent()
  }

  /**
   * Highlights the previous matching [Area] in the text. Wraps to the end before the first match.
   */
  fun highlightPrevMatch() {
    if (matches.size == 0) return
    if (currentMatch > 0) currentMatch--
    else currentMatch = matches.size - 1
    highlightCurrent()
  }

  private fun highlightCurrent() {
    highlight(matches[currentMatch], true)
    commitSpans()
    searchable.scrollTo(matches[currentMatch])
  }

  @UiThread
  private fun highlightMatches() {
    matches.forEachIndexed { idx, area ->
      highlight(area, false)
      if (currentMatch == idx) highlight(area, true)
    }
    commitSpans()
  }

  @UiThread
  private fun highlight(area: Area, isCurrentHighlight: Boolean) {
    val color = resources.getColor(
        if (isCurrentHighlight) R.color.textHighlightCurrent else R.color.textHighlightDefault,
        activity!!.theme)
    val span = BackgroundColorSpan(color)
    spannableText.setSpan(
        span, area.startPosition, area.endPosition, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (isCurrentHighlight) {
      searchLayout.editorSearchMatches.text =
          str(R.string.found_x_matches_current_y, matches.size, currentMatch + 1)
      currentHighlight.ifPresent { spannableText.removeSpan(it) }
      currentHighlight = span.opt()
    }
  }

  @UiThread
  private fun clear() {
    searchLayout.editorSearchMatches.text = ""
    currentMatch = 0
    matches.clear()
    currentHighlight = Empty()
    spannableText = SpannableString(searchable.text)
    commitSpans()
  }
}
