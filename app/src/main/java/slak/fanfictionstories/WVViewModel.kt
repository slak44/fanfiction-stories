package slak.fanfictionstories

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.anko.longToast
import slak.fanfictionstories.data.fetchers.*
import slak.fanfictionstories.utility.Static
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private object HTMLJSInterface {
  lateinit var lastHTML: String

  @JavascriptInterface
  @Suppress("unused")
  fun setInnerHTML(data: String) {
    Log.v(URL_TAG, "Received extracted HTML from @JSInterface")
    lastHTML = data
  }
}

private object FFWebClient : WebViewClient() {
  var currentCallback: ((url: String) -> Unit)? = null

  override fun onPageFinished(view: WebView?, url: String) {
    if (currentCallback != null) {
      Log.v(URL_TAG, "Page load finished for: $url")
      currentCallback?.invoke(url)
    }
  }
}

class WVViewModel(application: Application) : AndroidViewModel(application) {
  private var currentErrorRetries = RETRY_COUNT

  // This lives as long as the application, it's fine
  @SuppressLint("StaticFieldLeak")
  val webView = WebView(application)

  val defaultUserAgent: String = webView.settings.userAgentString

  private val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/99.0.4844.73 Safari/537.36"

  init {
    webView.webViewClient = FFWebClient

    // Clearly needed here
    @SuppressLint("SetJavaScriptEnabled")
    webView.settings.javaScriptEnabled = true
    webView.addJavascriptInterface(HTMLJSInterface, "Interface")

    webView.loadUrl("https://www.fanfiction.net")

    webView.visibility = View.GONE
  }

  @UiThread
  private fun cancel() {
    Log.v(URL_TAG, "Load cancelled, stop loading webview")
    webView.stopLoading()
    FFWebClient.currentCallback = null
    webView.loadUrl("https://www.fanfiction.net")
  }

  @UiThread
  fun addWebView(target: ViewGroup) {
    if (FFWebClient.currentCallback != null) {
      cancel()
    }
    if (webView.parent != null) {
      (webView.parent as ViewGroup).removeView(webView)
    }
    target.addView(webView, MATCH_PARENT, 700)
  }

  @UiThread
  private suspend fun getWebDocument(url: String, userAgent: String) = suspendCoroutine<String?> { continuation ->
    var currentWebViewRetries = RETRY_COUNT

    webView.settings.userAgentString = userAgent
    FFWebClient.currentCallback = cb@ {
      if (currentWebViewRetries == 0) {
        cancel()
        continuation.resume(null)
        return@cb
      }

      // Cloudflare... don't ask...
      val js = """
        (function() {
          window.speechSynthesis = {
            getVoices: () => [],
            set onvoiceschanged(cb) {
              setTimeout(() => {cb({})}, 67);
            }
          };
          Interface.setInnerHTML(document.documentElement.outerHTML);
        })()
      """.trimIndent()
      webView.evaluateJavascript(js) {
        val html = HTMLJSInterface.lastHTML
        if ("Just a moment..." in html || "Cloudflare" in html) {
          Log.v(URL_TAG, "Waiting for additional redirect")
          GlobalScope.launch(Main) {
            webView.visibility = View.VISIBLE
            delay(2000)
            webView.visibility = View.GONE
          }
          currentWebViewRetries--
          return@evaluateJavascript
        }
        FFWebClient.currentCallback = null
        continuation.resume(html)
      }
    }

    if (webView.url == url) {
      Log.v(URL_TAG, "Reusing webview because it already is on the target URL")
      FFWebClient.currentCallback?.invoke(url)
    } else {
      webView.loadUrl(url)
    }
  }

  /**
   * Wait for the document at the given [url] to load, and return the HTML contents.
   *
   * Call [onError] for every error, and retry if failed.
   */
  @AnyThread
  suspend fun patientlyFetchDocument(
      url: String,
      userAgent: String = desktopUserAgent,
      onError: (t: Throwable) -> Unit
  ): String? = withContext(networkContext) {
    waitForNetwork()
    delay(RATE_LIMIT_MS)
    Log.v(URL_TAG, "Waited for rate limit, retrying...")

    return@withContext try {
      withContext(Main) {
        val text = getWebDocument(url, userAgent)
        Notifications.ERROR.cancel()
        if (text == null) {
          Static.currentCtx.longToast(R.string.request_error)
        }
        text
      }
    } catch (t: Throwable) {
      Log.e(URL_TAG, "Failed to fetch url ($url)", t)
      onError(t)

      if (currentErrorRetries == 0) {
        Log.e(URL_TAG, "Retry count $RETRY_COUNT exceeded for $url")
        currentErrorRetries = RETRY_COUNT
        withContext(Main) {
          Static.currentCtx.longToast(R.string.request_error)
        }
        Notifications.ERROR.cancel()
        return@withContext null
      } else {
        currentErrorRetries--
      }

      patientlyFetchDocument(url, userAgent, onError)
    }
  }
}