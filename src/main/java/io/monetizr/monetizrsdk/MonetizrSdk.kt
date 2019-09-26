package io.monetizr.monetizrsdk

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.preference.PreferenceManager
import android.util.DisplayMetrics
import android.util.Log
import android.widget.ProgressBar
import android.widget.RelativeLayout
import androidx.appcompat.app.AlertDialog
import com.android.volley.Request
import io.monetizr.monetizrsdk.api.Telemetrics
import io.monetizr.monetizrsdk.api.WebApi
import io.monetizr.monetizrsdk.misc.ConfigHelper
import io.monetizr.monetizrsdk.misc.Parameters
import io.monetizr.monetizrsdk.provider.ActivityProvider
import io.monetizr.monetizrsdk.provider.ApplicationProvider
import io.monetizr.monetizrsdk.ui.activity.ProductActivity
import org.json.JSONObject
import java.util.*

class MonetizrSdk {

    companion object {
        var debuggable: Boolean = false
        private var initialLaunch: Boolean = true
        private var progressDialog: AlertDialog? = null

        fun showProductForTag(productTag: String) {
            try {
                val context = ApplicationProvider.application as Context
                val activity = ActivityProvider.currentActivity

                if (activity == null) {
                    logError("Activity context is null")
                    return
                }

                if (isNetworkAvailable(context) == false) {
                    logError("Did not have internet access")
                    return
                }

                val apiKey = ConfigHelper.getConfigValue(context, Parameters.RAW_API_KEY)
                val endpoint = ConfigHelper.getConfigValue(context, Parameters.RAW_API_ENDPOINT)

                createProgressDialog()
                showProgressBar()
                requestProductInformation(activity, productTag, endpoint, apiKey)
                sendTelemetricsInfo(activity)

            } catch (e: Exception) {
                logError(e)
            }
        }

        private fun sendTelemetricsInfo(context: Context) {
            Telemetrics.sendDeviceInfo()

            if (initialLaunch) {
                Telemetrics.session(ApplicationProvider.sessionStart)
                Telemetrics.sndFirstImpression()
                initialLaunch = false
            }

            val monetizrSdkPreference = PreferenceManager.getDefaultSharedPreferences(context)
            val isFirstRun = monetizrSdkPreference.getBoolean(Parameters.IS_FIRST_RUN, true)

            if (isFirstRun) {
                val editor = monetizrSdkPreference.edit()
                editor.putBoolean(Parameters.IS_FIRST_RUN, false)
                editor.apply()

                Telemetrics.sendFistRun()
            }

            val lastUpdateVersion = monetizrSdkPreference.getInt(Parameters.LAST_UPDATE_VERSION, BuildConfig.VERSION_CODE)
            val currentVersion = BuildConfig.VERSION_CODE

            if (lastUpdateVersion == currentVersion) {
                val edit = monetizrSdkPreference.edit()
                edit.putInt(Parameters.LAST_UPDATE_VERSION, lastUpdateVersion)
                edit.apply()
            } else if (lastUpdateVersion < currentVersion) {
                Telemetrics.update()
            }
        }

        @Suppress("DEPRECATION")
        private fun isNetworkAvailable(context: Context): Boolean {
            var result = false
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm?.run {
                    cm.getNetworkCapabilities(cm.activeNetwork)?.run {
                        result = when {
                            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                            else -> false
                        }
                    }
                }
            } else {
                cm?.run {
                    cm.activeNetworkInfo?.run {
                        if (type == ConnectivityManager.TYPE_WIFI) {
                            result = true
                        } else if (type == ConnectivityManager.TYPE_MOBILE) {
                            result = true
                        }
                    }
                }
            }
            return result
        }

        private fun requestProductInformation(activity: Activity, productTag: String, endpoint: String, apiKey: String) {
            val display = activity.windowManager.defaultDisplay

            val realMetrics = DisplayMetrics()
            display.getRealMetrics(realMetrics)
            val realWidth = realMetrics.widthPixels
            val language = Locale.getDefault().displayLanguage
            val url = endpoint + "products/tag/" + productTag + "?size=" + realWidth + "&language=" + language

            WebApi.getInstance(activity).makeRequest(url, Request.Method.GET, null, apiKey, { onProductSuccess(it, productTag) }, ::onProductFail)
        }

        private fun onProductSuccess(response: JSONObject, productTag: String) {
            hideProgressBar()
            Telemetrics.clickreward(productTag)
            showProductActivity(response, productTag)
        }

        private fun onProductFail(error: Throwable) {
            hideProgressBar()
            logError(error)
        }

        private fun showProductActivity(productInfo: JSONObject, productTag: String) {
            val currentActivity = ActivityProvider.currentActivity ?: return
            ProductActivity.start(currentActivity, productInfo.toString(), productTag)
        }

        private fun createProgressDialog() {
            val application = ActivityProvider.currentActivity as Context

            val holderLayout = RelativeLayout(application)
            val params = RelativeLayout.LayoutParams(200, 200)
            params.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            holderLayout.layoutParams = params

            val progressBar = ProgressBar(application)
            progressBar.isIndeterminate = true
            holderLayout.addView(progressBar, params)

            val alertBuilder = AlertDialog.Builder(application)
            alertBuilder.setCancelable(true)
            alertBuilder.setView(holderLayout)

            progressDialog = alertBuilder.create()
        }

        private fun logError(error: Throwable) {
            if (debuggable) {
                Log.i("MonetizrSDK", "has en error:  $error")
                error.printStackTrace()
            }
        }

        private fun logError(error: String) {
            if (debuggable) {
                Log.i("MonetizrSDK", "has en error:  $error")
            }
        }

        private fun showProgressBar() {
            progressDialog?.show()
        }

        private fun hideProgressBar() {
            progressDialog?.dismiss()
            progressDialog = null
        }
    }

}