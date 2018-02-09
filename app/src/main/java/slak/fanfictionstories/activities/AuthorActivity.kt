package slak.fanfictionstories.activities

import android.content.Intent
import android.os.Bundle
import android.support.design.widget.TabLayout
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.*
import kotlinx.android.synthetic.main.activity_author.*
import kotlinx.android.synthetic.main.fragment_author_bio.view.*
import kotlinx.android.synthetic.main.fragment_author_stories.view.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.Author
import slak.fanfictionstories.fetchers.getAuthor
import slak.fanfictionstories.utility.*
import java.util.*

class AuthorActivity : LoadingActivity(1) {
  companion object {
    const val INTENT_AUTHOR_ID = "author_id_intent"
    const val INTENT_AUTHOR_NAME = "author_name_intent"
    private const val RESTORE_AUTHOR = "author_restore"
  }

  private var author: Optional<Author> = Optional.empty()

  /**
   * The [android.support.v4.view.PagerAdapter] that will provide
   * fragments for each of the sections. We use a
   * {@link FragmentPagerAdapter} derivative, which will keep every
   * loaded fragment in memory. If this becomes too memory intensive, it
   * may be best to switch to a [android.support.v4.app.FragmentStatePagerAdapter].
   */
  private var sectionsPagerAdapter: SectionsPagerAdapter? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_author)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    val authorId = intent.getLongExtra(INTENT_AUTHOR_ID, -1L)
    if (authorId == -1L) throw IllegalArgumentException("Missing author id from intent")

    val authorName = intent.getStringExtra(INTENT_AUTHOR_NAME)
      ?: throw IllegalArgumentException("Missing author name from intent")

    title = authorName

    container.addOnPageChangeListener(TabLayout.TabLayoutOnPageChangeListener(tabs))
    tabs.addOnTabSelectedListener(TabLayout.ViewPagerOnTabSelectedListener(container))

    showLoading()
    launch(CommonPool) {
      val restoreAuthor = savedInstanceState?.getParcelable<Author>(RESTORE_AUTHOR)?.opt()
      author = restoreAuthor ?: getAuthor(authorId).await().opt()
      launch(UI) {
        if (menu.isPresent) onPrepareOptionsMenu(menu.get())
        sectionsPagerAdapter = SectionsPagerAdapter(supportFragmentManager)
        container.adapter = sectionsPagerAdapter
        hideLoading()
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable(RESTORE_AUTHOR,
        author.orElseThrow(IllegalStateException("Saving state of Author that does not exist")))
  }

  private var menu: Optional<Menu> = Optional.empty()

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_author, menu)
    this.menu = menu.opt()
    return true
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.favoriteAuthor).iconTint(R.color.white, theme)
    // This can be called before the author is filled
    if (author.isPresent && author.get().favoriteAuthors.isEmpty()) {
      menu.findItem(R.id.favoritedAuthors).isVisible = false
    }
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.followAuthor -> {
        // FIXME: follow author
      }
      R.id.favoriteAuthor -> {
        // FIXME: favorite author
      }
      R.id.privateMessage -> {
        // FIXME: send private message
      }
      R.id.favoritedAuthors -> {
        val author = author.orElseThrow(IllegalStateException("Menu item clicked, but no Author"))
        AlertDialog.Builder(this)
            .setTitle(R.string.favorite_authors)
            .setItems(author.favoriteAuthors.map { it.second }.toTypedArray(), { d, which ->
              d.dismiss()
              val intent = Intent(this, AuthorActivity::class.java)
              intent.putExtra(INTENT_AUTHOR_NAME, author.favoriteAuthors[which].second)
              intent.putExtra(INTENT_AUTHOR_ID, author.favoriteAuthors[which].first)
              startActivity(intent)
            }).show()
      }
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }

  /**
   * A [FragmentPagerAdapter] that returns a fragment corresponding to the tabs.
   */
  inner class SectionsPagerAdapter(fm: FragmentManager) : FragmentPagerAdapter(fm) {
    override fun getItem(position: Int): Fragment = when (position) {
      0 -> HtmlFragment.newInstance(author.get().bioHtml)
      1 -> StoryListFragment.newInstance(ArrayList(author.get().userStories))
      2 -> StoryListFragment.newInstance(ArrayList(author.get().favoriteStories))
      else -> throw IllegalStateException("getCount returned too many tabs")
    }
    override fun getCount(): Int = 3 // tabs
  }

  /**
   * Fragment that renders HTML in a [android.widget.TextView].
   */
  internal class HtmlFragment : Fragment() {
    companion object {
      private const val ARG_HTML_TEXT = "html_text"

      /**
       * Returns a new instance of this fragment for the given HTML text.
       */
      fun newInstance(html: String): HtmlFragment {
        val fragment = HtmlFragment()
        val args = Bundle()
        args.putString(ARG_HTML_TEXT, html)
        fragment.arguments = args
        return fragment
      }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
      val rootView = inflater.inflate(R.layout.fragment_author_bio, container, false)
      rootView.html.text = Html.fromHtml(arguments!!.getString(ARG_HTML_TEXT),
          Html.FROM_HTML_MODE_LEGACY, null, HrSpan.tagHandlerFactory(rootView.width))
      rootView.html.movementMethod = LinkMovementMethod.getInstance()
      return rootView
    }
  }

  /**
   * Fragment that lists stories using a [android.support.v7.widget.RecyclerView] and [StoryAdapter].
   */
  internal class StoryListFragment : Fragment() {
    companion object {
      private const val ARG_STORIES = "stories"

      /**
       * Returns a new instance of this fragment for the given stories.
       */
      fun newInstance(stories: ArrayList<StoryModel>): StoryListFragment {
        val fragment = StoryListFragment()
        val args = Bundle()
        args.putParcelableArrayList(ARG_STORIES, stories)
        fragment.arguments = args
        return fragment
      }
    }

    private lateinit var adapter: StoryAdapter
    private lateinit var stories: ArrayList<StoryModel>

    private var arrangement = Arrangement(
        OrderStrategy.TITLE_ALPHABETIC,
        OrderDirection.DESC,
        GroupStrategy.NONE
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
      val rootView = inflater.inflate(R.layout.fragment_author_stories, container, false)
      stories = arguments!!.getParcelableArrayList(ARG_STORIES)
      rootView.stories.layoutManager = LinearLayoutManager(context)
      StoryCardView.createRightSwipeHelper(rootView.stories, { intent, _ -> startActivity(intent) })
      adapter = StoryAdapter(context!!)
      rootView.stories.adapter = adapter
      if (stories.isEmpty()) {
        rootView.noStories.visibility = View.VISIBLE
        rootView.orderBy.visibility = View.GONE
        rootView.groupBy.visibility = View.GONE
      } else {
        rootView.noStories.visibility = View.GONE
        rootView.orderBy.visibility = View.VISIBLE
        rootView.groupBy.visibility = View.VISIBLE
        adapter.arrangeStories(stories, arrangement)
      }
      rootView.orderBy.setOnClickListener {
        orderByDialog(context!!, arrangement.orderStrategy, arrangement.orderDirection) { str, dir ->
          arrangement = Arrangement(
              orderDirection = dir, orderStrategy = str, groupStrategy = arrangement.groupStrategy)
          adapter.arrangeStories(stories, arrangement)
        }
      }
      rootView.groupBy.setOnClickListener {
        groupByDialog(context!!, arrangement.groupStrategy) {
          arrangement = Arrangement(arrangement.orderStrategy, arrangement.orderDirection, it)
          adapter.arrangeStories(stories, arrangement)
        }
      }
      return rootView
    }
  }
}
