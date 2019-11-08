package com.themonetizr.monetizrsdk.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.volley.Request
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wallet.*
import com.themonetizr.monetizrsdk.ClearedService
import com.themonetizr.monetizrsdk.MonetizrSdk
import com.themonetizr.monetizrsdk.MonetizrSdk.Companion.logError
import com.themonetizr.monetizrsdk.R
import com.themonetizr.monetizrsdk.api.Telemetrics
import com.themonetizr.monetizrsdk.api.WebApi
import com.themonetizr.monetizrsdk.dto.*
import com.themonetizr.monetizrsdk.misc.ConfigHelper
import com.themonetizr.monetizrsdk.misc.Parameters
import com.themonetizr.monetizrsdk.payment.PaymentsUtil
import com.themonetizr.monetizrsdk.ui.adapter.ImageGalleryAdapter
import com.themonetizr.monetizrsdk.ui.adapter.ItemIndicator
import com.themonetizr.monetizrsdk.ui.adapter.ItemSnapHelper
import com.themonetizr.monetizrsdk.ui.dialog.OptionsDialog
import com.themonetizr.monetizrsdk.ui.dialog.OptionsDialogListener
import com.themonetizr.monetizrsdk.ui.dialog.ShippingRateDialog
import com.themonetizr.monetizrsdk.ui.dialog.ShippingRateDialogListener
import com.themonetizr.monetizrsdk.ui.helpers.ProgressDialogBuilder
import kotlinx.android.synthetic.main.activity_product.*
import org.json.JSONObject
import java.io.Serializable

class ProductActivity : AppCompatActivity(), ShippingRateDialogListener, OptionsDialogListener {
    private var userMadeInteraction: Boolean = false
    private var activityLaunchedStamp: Long = 0
    private lateinit var paymentsClient: PaymentsClient
    private val selectedOptions: ArrayList<String> = ArrayList()
    private var progressDialog: AlertDialog? = null
    private var chosenVariant: Variant? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_product)
        activityLaunchedStamp = System.currentTimeMillis()
        paymentsClient = PaymentsUtil.createPaymentsClient(this)
        progressDialog = ProgressDialogBuilder.makeProgressDialog(this)
        startService(Intent(baseContext, ClearedService::class.java))

        val tag = intent!!.getStringExtra(Parameters.PRODUCT_TAG)!!
        val json = intent!!.getStringExtra(Parameters.PRODUCT_JSON)!!
        val productJson = JSONObject(json)
        val product = Product(productJson)

        initImageAdapter(product.images)
        // Show Google Pay if it is available in specified country
        initGooglePayButton()
        initCheckoutTitle(product)

        if (product.variants.isEmpty() == false) {
            variantContainerView.isEnabled = true
            var first = product.getFirstVariant()!!

            // On orientation change chosen variant is being saved so it can be restored
            if (savedInstanceState?.containsKey(CHOSEN_VARIANT_KEY) != null ) {
                first = savedInstanceState.getSerializable(CHOSEN_VARIANT_KEY) as Variant
            }
            this.chosenVariant = first
            initProductPriceTitle(first)
            initProductVariantsTitle(first)
            initDefaultSelected(first)
        } else {
            variantContainerView.isEnabled = false
        }

        productTitleView.text = product.title
        productDescriptionView.text = product.descriptionIos

        closeButtonView.setOnClickListener { finish() }
        payButtonView.setOnClickListener { payGooglePlayTap(productJson) }

        variantContainerView.setOnClickListener { showOptionDialog(json) }
        checkoutButtonView.setOnClickListener { checkout(null, tag, productJson) }

        hideStatusBar()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        userMadeInteraction = true
    }


    override fun onStop() {
        super.onStop()
        val productTag = intent!!.getStringExtra(Parameters.PRODUCT_TAG)
        val productVisibleTime = System.currentTimeMillis() - activityLaunchedStamp
        Telemetrics.impressionvisible(productVisibleTime, productTag)

        if (userMadeInteraction == false) {
            Telemetrics.dismiss(productTag)
        }
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideStatusBar()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideStatusBar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState?.putStringArrayList(SELECTED_OPTIONS_KEY, selectedOptions)
        outState?.putSerializable(CHOSEN_VARIANT_KEY, this.chosenVariant as Serializable)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState?.let {
            if (it.containsKey(SELECTED_OPTIONS_KEY)) {
                val restored = it.getStringArrayList(SELECTED_OPTIONS_KEY)
                if (restored != null) {
                    this.selectedOptions.clear()
                    this.selectedOptions.addAll(restored)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == LOAD_PAYMENT_DATA_REQUEST_CODE && resultCode == RESULT_OK) {
            if (data != null) {
                val paymentData = PaymentData.getFromIntent(data)
                if (paymentData != null) {
                    val tag = intent.getStringExtra(Parameters.PRODUCT_TAG)!!
                    val json = intent.getStringExtra(Parameters.PRODUCT_JSON)!!
                    val productJson = JSONObject(json)

                    checkout(paymentData, tag, productJson)
                }
            } else {
                val tag = intent.getStringExtra(Parameters.PRODUCT_TAG)!!
                val json = intent.getStringExtra(Parameters.PRODUCT_JSON)!!
                val productJson = JSONObject(json)
                checkout(null, tag, productJson)
            }
        }

        if (requestCode == LOAD_PAYMENT_DATA_REQUEST_CODE && resultCode == AutoResolveHelper.RESULT_ERROR) {
            AutoResolveHelper.getStatusFromIntent(data)?.let { handleError(it.statusCode) }
        }
        payButtonView.isEnabled = true
    }

    override fun onOptionsSelect(options: ArrayList<HierarchyVariant>) {
        hideStatusBar()
        selectedOptions.clear()
        for (option in options) {
            selectedOptions.add(option.id)
        }
        initProductVariantsValues(selectedOptions)
        updateProductPriceValues()
    }

    override fun onShippingRateSelect(paymentData: String, checkout: JSONObject, shippingRate: ShippingRate) {
        showProgressDialog()
        val apiKey = ConfigHelper.getConfigValue(this, Parameters.RAW_API_KEY)
        val apiAddress = ConfigHelper.getConfigValue(this, Parameters.RAW_API_ENDPOINT)
        val url = apiAddress + "products/checkoutwithpayment"
        val tag = intent.getStringExtra(Parameters.PRODUCT_TAG)!!
        val data = PaymentData.fromJson(paymentData)
        val addressJson = if (data.shippingAddress != null) {
            val address = data.shippingAddress!!
            ShippingAddressInto(address.name, address.name, address.address1, address.address2, address.locality, address.administrativeArea, address.countryCode, address.postalCode).getJsonObject()
        } else {
            JSONObject()
        }
        val json = intent.getStringExtra(Parameters.PRODUCT_JSON)!!
        val productJson = JSONObject(json)
        val variant = searchSelectedVariant(productJson)

        val body = CheckoutWithPaymentBody.createBody(paymentData, checkout, tag, variant, addressJson, shippingRate)

        WebApi.getInstance(this).makeRequest(url, Request.Method.POST, body, apiKey, {
            hideProgressDialog()
            MonetizrSdk.logError("success response for checkoutwith payment" + it.toString())
            finish()
        }, {
            hideProgressDialog()
            logError(it)
        })
    }

    //region checkout

    private fun checkout(proceedWithPayment: PaymentData? = null, productTag: String, product: JSONObject) {
        val variant: JSONObject? = searchSelectedVariant(product)
        val apiKey = ConfigHelper.getConfigValue(this, Parameters.RAW_API_KEY)
        val apiAddress = ConfigHelper.getConfigValue(this, Parameters.RAW_API_ENDPOINT)
        val url = apiAddress + "products/checkout"
        val withPayment = proceedWithPayment != null
        if (variant != null) {
            showProgressDialog()
            val jsonBody = CheckoutBody.createBody(variant, productTag, proceedWithPayment)

            if (firstCheckout) {
                Telemetrics.firstimpressioncheckout()
                firstCheckout = false
            }

            WebApi.getInstance(this).makeRequest(
                url, Request.Method.POST, jsonBody, apiKey,
                { response ->
                    hideProgressDialog()
                    val checkoutCreate = response.getJSONObject("data").getJSONObject("checkoutCreate")
                    val checkout = checkoutCreate.getJSONObject("checkout")

                    if (withPayment) {
                        showShippingDialog(proceedWithPayment!!.toJson(), checkout)
                    } else {
                        val checkoutErrors = checkoutCreate.getJSONArray("checkoutUserErrors")
                        val checkoutRedirect = checkout.getString("webUrl")

                        if (checkoutErrors.length() <= 0) {
                            showProductView(checkoutRedirect)
                        }
                    }
                },
                {
                    hideProgressDialog()
                    logError(it)
                }
            )
        }
    }

    private fun showProductView(url: String?) {
        try {
            val starter = Intent(this@ProductActivity, CheckoutViewActivity::class.java)
            starter.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            starter.putExtra(CheckoutViewActivity.WEBSITE_ADDRESS, url)
            startActivity(starter)

        } catch (error: Throwable) {
        }
    }

    //endregion

    //region ui

    private fun hideStatusBar() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LOW_PROFILE or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    private fun initGooglePayButton() {
        val isReadyToPayJson = PaymentsUtil.isReadyToPayRequest() ?: return
        val request = IsReadyToPayRequest.fromJson(isReadyToPayJson.toString()) ?: return

        paymentsClient.isReadyToPay(request).addOnCompleteListener { completedTask ->
            completedTask.getResult(ApiException::class.java)?.let(::showGooglePayButtonIfAvailable)
        }
    }

    private fun showGooglePayButtonIfAvailable(available: Boolean) {
        if (available) {
            payButtonView.visibility = View.VISIBLE
        } else {
            payButtonView.visibility = View.GONE
        }
    }

    private fun initImageAdapter(photos: ArrayList<String>) {
        val imageGalleryAdapter = ImageGalleryAdapter(this, photos)
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        productImagesView.setHasFixedSize(true)
        productImagesView.layoutManager = layoutManager
        ItemSnapHelper().attachToRecyclerView(productImagesView)
        productImagesView.addItemDecoration(ItemIndicator())
        productImagesView.adapter = imageGalleryAdapter
    }

    private fun initProductPriceTitle(variant: Variant) {
        productPriceView.text = variant.priceV2.formatString()
        if (variant.compareAtPriceV2 != null) {
            productDiscountView.text = variant.compareAtPriceV2?.formatString()
            productDiscountView.paintFlags = productDiscountView.paintFlags or STRIKE_THRU_TEXT_FLAG
        } else {
            productDiscountView.text = ""
        }
    }

    private fun initProductVariantsTitle(variant: Variant) {
        if (variant.selectedOptions.size > 0) {
            option1NameView.text = variant.selectedOptions[0].name
            option1ValueView.text = variant.selectedOptions[0].value

            option1NameView.visibility = View.VISIBLE
            option1ValueView.visibility = View.VISIBLE
        } else {
            option1NameView.visibility = View.GONE
            option1ValueView.visibility = View.GONE
        }

        if (variant.selectedOptions.size > 1) {
            option2NameView.text = variant.selectedOptions[1].name
            option2ValueView.text = variant.selectedOptions[1].value

            option2NameView.visibility = View.VISIBLE
            option2ValueView.visibility = View.VISIBLE
        } else {
            option2NameView.visibility = View.GONE
            option2ValueView.visibility = View.GONE
        }

        if (variant.selectedOptions.size > 2) {
            option3NameView.text = variant.selectedOptions[2].name
            option3ValueView.text = variant.selectedOptions[2].value

            option3NameView.visibility = View.VISIBLE
            option3ValueView.visibility = View.VISIBLE
        } else {
            option3NameView.visibility = View.GONE
            option3ValueView.visibility = View.GONE
        }
    }

    private fun initProductVariantsValues(options: ArrayList<String>) {
        if (options.isEmpty() == false) {

            if (options.size > 0) {
                option1ValueView.text = options[0]
            }

            if (options.size > 1) {
                option2ValueView.text = options[1]
            }

            if (options.size > 2) {
                option3ValueView.text = options[2]
            }
        }
    }

    private fun updateProductPriceValues() {
        val json = intent.getStringExtra(Parameters.PRODUCT_JSON)!!
        val selectedVariant = searchSelectedVariant(JSONObject(json))
        if (selectedVariant != null) {
            val variant = Variant(selectedVariant)
            this.chosenVariant = variant
            initProductPriceTitle(variant)
        }
    }

    private fun initCheckoutTitle(product: Product) {
        if (product.buttonTitle != null && product.buttonTitle.isNotEmpty()) {
            checkoutButtonView.text = product.buttonTitle
        }
    }

    private fun showOptionDialog(product: String) {
        val fragment = OptionsDialog.newInstance(product, selectedOptions)
        fragment.show(supportFragmentManager, "Option")
    }

    private fun payGooglePlayTap(productJson: JSONObject) {
        payButtonView.isEnabled = false
        val variantForCheckout: JSONObject? = searchSelectedVariant(productJson)
        if (variantForCheckout != null) {
            val totalPrice = variantForCheckout.getJSONObject("priceV2").getString("amount")

            val paymentDataRequestJson = PaymentsUtil.getPaymentDataRequest(totalPrice)
            if (paymentDataRequestJson == null) {
                logError("Can't fetch payment data request")
                return
            }
            val request = PaymentDataRequest.fromJson(paymentDataRequestJson.toString())

            if (request != null) {
                AutoResolveHelper.resolveTask(paymentsClient.loadPaymentData(request), this, LOAD_PAYMENT_DATA_REQUEST_CODE)
            }
        } else {
            logError("Can't fetch payment data request")
        }
        payButtonView.isEnabled = true
    }

    private fun showProgressDialog() {
        progressDialog?.show()
    }

    private fun hideProgressDialog() {
        progressDialog?.dismiss()
    }

    private fun showShippingDialog(paymentInfo: String, checkoutInfo: JSONObject) {
        val shippingDialog = ShippingRateDialog.newInstance(paymentInfo, checkoutInfo)
        shippingDialog.show(supportFragmentManager, ShippingRateDialog.TAG)
    }

    //endregion

    //region variants
    private fun searchSelectedVariant(product: JSONObject): JSONObject? {
        if (selectedOptions.isEmpty() == false) {
            if (product.has("variants")) {
                val variantsArray = product.getJSONObject("variants").getJSONArray("edges")
                for (variantIndex in 0 until variantsArray.length()) {
                    val variant = variantsArray.getJSONObject(variantIndex).getJSONObject("node")
                    val variantSelectedOptions = variant.getJSONArray("selectedOptions")
                    var numberOfMatchingOptions = 0

                    for (optionIndex in 0 until variantSelectedOptions.length()) {
                        val optionValue = variantSelectedOptions.getJSONObject(optionIndex).getString("value")

                        for (userSelected in selectedOptions) {
                            if (userSelected == optionValue) {
                                numberOfMatchingOptions++
                                break
                            }
                        }
                    }

                    if (numberOfMatchingOptions == variantSelectedOptions.length()) {
                        return variant
                    }
                }
            }
        }

        return null
    }

    private fun initDefaultSelected(variant: Variant) {
        for (option in variant.selectedOptions) {
            selectedOptions.add(option.value)
        }
    }
    //endregion

    private fun handleError(statusCode: Int) {
        Log.w("loadPaymentData failed", String.format("Error code: %d", statusCode))
    }

    companion object {
        var firstCheckout: Boolean = true
        const val LOAD_PAYMENT_DATA_REQUEST_CODE = 991
        const val SELECTED_OPTIONS_KEY = "SELECTED_OPTIONS_KEY"
        const val CHOSEN_VARIANT_KEY = "CHOSEN_VARIANT_KEY"

        fun start(context: Context, productJson: String, productTag: String) {
            val starter = Intent(context, ProductActivity::class.java)
            starter.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            starter.putExtra(Parameters.PRODUCT_JSON, productJson)
            starter.putExtra(Parameters.PRODUCT_TAG, productTag)
            context.startActivity(starter)
        }
    }
}
