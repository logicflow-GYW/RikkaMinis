package com.rikkaminis.app.provider

/**
 * Builtin catalogs are a network-failure net for official endpoints only.
 * A rejected credential (401/403) must never be reported as a successful list.
 */
public fun usesBuiltinCatalogOnFailure(isCustom: Boolean, statusCode: Int): Boolean =
    !isCustom && statusCode != 401 && statusCode != 403
