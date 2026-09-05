package com.openminis.app.data

import com.openminis.app.data.model.CustomHeader

/**
 * [T-provider-extra-headers] Apply user-authored headers to an OkHttp
 * request builder. Same-name REPLACE semantics over every default — this
 * is intentional (users override auth/UA for odd relays) and the UI must
 * label it as advanced/dangerous.
 */
fun okhttp3.Request.Builder.applyCustomHeaders(headers: List<CustomHeader>): okhttp3.Request.Builder {
    for (h in headers) {
        val name = h.name.trim()
        if (name.isEmpty()) continue
        header(name, h.value)
    }
    return this
}
