package io.monetizr.monetizrsdk.dto

import org.json.JSONObject

class Price {
    private val currencyCode: String
    private val amount: Int
    private val currency: String

    constructor(json: JSONObject) {
        if (json.has("currencyCode")) {
            this.currencyCode = json.getString("currencyCode")
        } else {
            this.currencyCode = ""
        }

        if (json.has("amount")) {
            this.amount = json.getInt("amount")
        } else {
            this.amount = 0
        }

        if (json.has("currency")) {
            this.currency = json.getString("currency")
        } else {
            this.currency = ""
        }
    }

    public fun formatString(): String {
        // TODO currency formatter
        return "$currency $amount"
    }
}