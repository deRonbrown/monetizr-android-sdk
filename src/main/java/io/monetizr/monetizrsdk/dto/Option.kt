package io.monetizr.monetizrsdk.dto

import org.json.JSONArray
import org.json.JSONObject

class Option {
    val name: String
    val values: ArrayList<String>

    constructor(json: JSONObject) {
        this.name = json.getString("name")
        this.values = ArrayList()

        if (json.has("values")) {
            val some = json.get("values")
            if (some is JSONArray) {
                val array = json.getJSONArray("values")
                for (i in 0 until array.length()) {
                    this.values.add(array.getString(i))
                }
            }
        }

        if (json.has("value")) {
            val some = json.getString("value")
            this.values.add(some)
        }

    }
}
