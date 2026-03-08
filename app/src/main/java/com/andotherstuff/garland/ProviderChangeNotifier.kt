package com.andotherstuff.garland

import android.content.Context
import android.provider.DocumentsContract

object GarlandProviderContract {
    const val AUTHORITY = "com.andotherstuff.garland.documents"
    const val ROOT_ID = "garland-root"
    const val ROOT_DOCUMENT_ID = "root"
    private const val SEARCH_PREFS = "garland-provider-search"
    private const val SEARCH_QUERIES_KEY = "queries"
    private const val MAX_TRACKED_SEARCH_QUERIES = 8

    fun loadTrackedSearchQueries(context: Context): List<String> {
        return context.getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE)
            .getString(SEARCH_QUERIES_KEY, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
    }

    fun trackSearchQuery(context: Context, rawQuery: String) {
        val query = rawQuery.trim().lowercase()
        if (query.isBlank()) return

        val queries = LinkedHashSet(loadTrackedSearchQueries(context))
        queries.remove(query)
        queries.add(query)
        while (queries.size > MAX_TRACKED_SEARCH_QUERIES) {
            queries.remove(queries.first())
        }

        context.getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SEARCH_QUERIES_KEY, queries.joinToString("\n"))
            .apply()
    }
}

class ProviderChangeNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun notifyDocumentChanged(documentId: String) {
        val resolver = appContext.contentResolver
        val uris = linkedSetOf(
            DocumentsContract.buildDocumentUri(GarlandProviderContract.AUTHORITY, documentId),
            DocumentsContract.buildDocumentUri(
                GarlandProviderContract.AUTHORITY,
                GarlandProviderContract.ROOT_DOCUMENT_ID,
            ),
            DocumentsContract.buildChildDocumentsUri(
                GarlandProviderContract.AUTHORITY,
                GarlandProviderContract.ROOT_DOCUMENT_ID,
            ),
            DocumentsContract.buildRecentDocumentsUri(
                GarlandProviderContract.AUTHORITY,
                GarlandProviderContract.ROOT_ID,
            ),
            DocumentsContract.buildRootsUri(GarlandProviderContract.AUTHORITY),
        )
        GarlandProviderContract.loadTrackedSearchQueries(appContext).forEach { query ->
            uris += DocumentsContract.buildSearchDocumentsUri(
                GarlandProviderContract.AUTHORITY,
                GarlandProviderContract.ROOT_ID,
                query,
            )
        }
        uris.forEach { uri -> resolver.notifyChange(uri, null) }
    }
}
