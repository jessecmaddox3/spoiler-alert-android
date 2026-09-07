package com.jessemaddox.spoileralert.ui

import com.jessemaddox.spoileralert.data.VaultEntity

/** Exact identity of the self-posted onboarding fake. Real notifications may share the same
 * team shield while the demo is armed, so shieldId alone is never sufficient. */
data class DemoNotificationIdentity(
    val shieldId: Long,
    val sourcePackage: String,
    val title: String,
    val text: String,
    val postedAfterMillis: Long,
) {
    fun matches(row: VaultEntity): Boolean =
        row.shieldId == shieldId &&
            row.sourcePackage == sourcePackage &&
            row.title == title &&
            row.text == text &&
            row.postedAtMillis >= postedAfterMillis
}
