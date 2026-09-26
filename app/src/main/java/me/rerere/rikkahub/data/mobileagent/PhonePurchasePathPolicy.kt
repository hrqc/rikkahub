package me.rerere.rikkahub.data.mobileagent

/** Labels may be on a button's child or its container; neither direction may bypass the guard. */
internal fun isPhonePurchasePathRestricted(path: List<Int>, restrictedPaths: List<List<Int>>): Boolean =
    restrictedPaths.any { restricted ->
        restricted.take(path.size) == path || path.take(restricted.size) == restricted
    }
