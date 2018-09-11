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
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.R
import slak.fanfictionstories.printAll

/** A class that implements this interface can be searched using [SearchHighlighter]. */
interface Searchable {
  var text: SpannableString

  @AnyThread
  suspend fun commit()

  /** Scroll to the specified [Area] within the text element. */
  fun scrollTo(area: Area)
}

data class Area(val startPosition: Int, val length: Int) {
  val endPosition: Int get() = startPosition + length
}

data class Match(val area: Area, val searchable: Searchable) {
  fun highlightAsCurrent() {
    current.ifPresent { searchable.text.removeSpan(it) }
    current = this.opt()
    highlight()
    scrollIntoView()
  }

  fun highlight() {
    printAll(this, current.orNull(), this == current.orNull())
    val span = BackgroundColorSpan(if (this === current.orNull()) currentColor else defaultColor)
    searchable.text.setSpan(span,
        area.startPosition, area.endPosition, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
  }

  fun scrollIntoView() = searchable.scrollTo(area)

  companion object {
    private val currentColor by lazy {
      Static.res.getColor(R.color.textHighlightCurrent, Static.currentCtx.theme)
    }
    private val defaultColor by lazy {
      Static.res.getColor(R.color.textHighlightDefault, Static.currentCtx.theme)
    }
    var current: Optional<Match> = Empty()
      private set
  }
}

/** This class stores search state and provides logic for interacting with it. */
class SearchHighlighter : Fragment() {
  private var currentHighlightIdx: Int = 0
  private var matches: List<Match> = emptyList()

  private lateinit var searchable: Searchable

  fun setSearchable(s: Searchable) {
    searchable = s
  }

  companion object {
    private const val TAG = "SearchHighlighter"
    private const val RESTORE_LAYOUT_VISIBILITY = "search_is_visible"
  }

  init {
    retainInstance = true
  }

  private lateinit var searchLayout: ConstraintLayout

  override fun onCreateView(inflater: LayoutInflater,
                            container: ViewGroup?, savedInstanceState: Bundle?): View? {
    searchLayout = inflater.inflate(
        R.layout.fragment_search_ui, container, false) as ConstraintLayout
    Log.v(TAG, "Search UI inflated")
    fun updateMatchText() {
      searchLayout.editorSearchMatches.text =
          str(R.string.found_x_matches_current_y, matches.size, currentHighlightIdx + 1)
    }
    searchLayout.searchNextMatchBtn.setOnClickListener {
      if (matches.isEmpty()) return@setOnClickListener
      launch(UI) {
        if (currentHighlightIdx + 1 != matches.size) currentHighlightIdx++
        else currentHighlightIdx = 0
        matches[currentHighlightIdx].highlightAsCurrent()
        updateMatchText()
        searchable.commit()
      }
    }
    searchLayout.searchPrevMatchBtn.setOnClickListener {
      if (matches.isEmpty()) return@setOnClickListener
      launch(UI) {
        if (currentHighlightIdx > 0) currentHighlightIdx--
        else currentHighlightIdx = matches.size - 1
        matches[currentHighlightIdx].highlightAsCurrent()
        updateMatchText()
        searchable.commit()
      }
    }
    searchLayout.searchCloseBtn.setOnClickListener {
      launch(UI) {
        searchLayout.visibility = View.GONE
        searchable.text.removeAllSpans()
        searchable.commit()
        Static.imm.hideSoftInputFromWindow(searchLayout.windowToken, 0)
      }
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
      if (searchLayout.visibility == View.VISIBLE && matches.isNotEmpty()) {
        Match.current.ifPresent { it.scrollIntoView() }
      }
    }
    searchLayout.editorSearch.setOnEditorActionListener { _, actionId, _ ->
      if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
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
        searchAndHighlight()
      }
    })
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt(RESTORE_LAYOUT_VISIBILITY, searchLayout.visibility)
  }

  /** Go through the text and return the [Match]es of the [searchQuery]. */
  @AnyThread
  private fun searchText(searchQuery: String): List<Match> {
    if (searchQuery.isEmpty()) throw IllegalArgumentException("Search query is empty string")
    var startIdx = searchable.text.indexOf(searchQuery)
    val results = mutableListOf<Match>()
    while (startIdx != -1) {
      val p = Area(startIdx, searchQuery.length)
      results.add(Match(p, searchable))
      startIdx = searchable.text.indexOf(searchQuery, p.endPosition)
    }
    return results
  }

  @AnyThread
  private fun searchAndHighlight() = launch(UI) {
    clear()
    val toFind = searchLayout.editorSearch.text.toString()
    if (toFind.isEmpty()) {
      // Empty query means there are no matches, but that's obvious, so just leave this text empty
      searchLayout.editorSearchMatches.text = ""
      return@launch
    }
    matches = searchText(toFind)
    matches.forEach { it.highlight() }
    if (matches.isNotEmpty()) {
      searchLayout.editorSearchMatches.text =
          str(R.string.found_x_matches_current_y, matches.size, 1)
      matches[0].highlightAsCurrent()
      searchable.commit()
    } else {
      searchLayout.editorSearchMatches.text = str(R.string.no_matches_found)
    }
  }

  /** Make the searching UI visible, and bring it into focus. */
  @UiThread
  fun show() {
    searchLayout.visibility = View.VISIBLE
    searchLayout.editorSearch.requestFocus()
  }

  @UiThread
  private fun clear() {
    searchLayout.editorSearchMatches.text = ""
    currentHighlightIdx = 0
    matches = emptyList()
//    currentHighlight = Empty()
    searchable.text.removeAllSpans()
  }
}
