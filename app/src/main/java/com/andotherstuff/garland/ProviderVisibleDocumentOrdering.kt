package com.andotherstuff.garland

object ProviderVisibleDocumentOrdering {
    fun sortRecentFirst(records: List<LocalDocumentRecord>): List<LocalDocumentRecord> {
        return records.sortedWith(
            compareByDescending<LocalDocumentRecord> { it.updatedAt }
                .thenBy { it.displayName.lowercase() }
        )
    }
}
