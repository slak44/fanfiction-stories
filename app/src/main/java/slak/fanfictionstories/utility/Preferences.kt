package slak.fanfictionstories.utility

import slak.fanfictionstories.Arrangement
import slak.fanfictionstories.GroupStrategy
import slak.fanfictionstories.OrderDirection
import slak.fanfictionstories.OrderStrategy

object Prefs {
  const val PREFS_FILE = "slak.fanfictionstories.SHARED_PREFERENCES"

  const val RESUME_STORY_ID = "resume_story_id"
  const val LIST_GROUP_STRATEGY = "list_group_strategy"
  const val LIST_ORDER_STRATEGY = "list_order_strategy"
  const val LIST_ORDER_IS_REVERSE = "list_order_strategy_rev"

  var groupStrategy
    get() = GroupStrategy[Static.prefs.getInt(LIST_GROUP_STRATEGY, GroupStrategy.NONE.ordinal)]
    set(new) { usePrefs { it.putInt(LIST_GROUP_STRATEGY, new.ordinal) } }

  var orderStrategy
    get() = OrderStrategy[Static.prefs.getInt(LIST_ORDER_STRATEGY, OrderStrategy.TITLE_ALPHABETIC.ordinal)]
    set(new) { usePrefs { it.putInt(LIST_ORDER_STRATEGY, new.ordinal) } }

  var orderDirection
    get() = OrderDirection[Static.prefs.getInt(LIST_ORDER_IS_REVERSE, OrderDirection.DESC.ordinal)]
    set(new) { usePrefs { it.putInt(LIST_ORDER_IS_REVERSE, new.ordinal) } }

  fun arrangement() = Arrangement(orderStrategy, orderDirection, groupStrategy)
}
