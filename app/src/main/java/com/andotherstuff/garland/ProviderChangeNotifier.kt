package com.andotherstuff.garland

import android.content.Context
import android.net.Uri
import java.net.URLEncoder

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

    fun changedUriStrings(documentId: String, trackedQueries: List<String>): Set<String> {
        val rootTreeUri = treeDocumentUriString(ROOT_DOCUMENT_ID)
        return linkedSetOf(
            documentUriString(documentId),
            documentUriString(ROOT_DOCUMENT_ID),
            documentUriUsingTreeString(rootTreeUri, documentId),
            documentUriUsingTreeString(rootTreeUri, ROOT_DOCUMENT_ID),
            childDocumentsUriString(ROOT_DOCUMENT_ID),
            childDocumentsUriUsingTreeString(rootTreeUri, ROOT_DOCUMENT_ID),
            recentDocumentsUriString(ROOT_ID),
            rootsUriString(),
        ).apply {
            trackedQueries.forEach { query ->
                add(searchDocumentsUriString(ROOT_ID, query))
            }
        }
    }

    fun changedUris(documentId: String, trackedQueries: List<String>): Set<Uri> {
        return changedUriStrings(documentId, trackedQueries).mapTo(linkedSetOf(), Uri::parse)
    }

    private fun rootsUriString(): String = "$CONTENT_URI_PREFIX/root"

    private fun recentDocumentsUriString(rootId: String): String {
        return "$CONTENT_URI_PREFIX/root/$rootId/recent"
    }

    private fun searchDocumentsUriString(rootId: String, query: String): String {
        return "$CONTENT_URI_PREFIX/root/${encodePathSegment(rootId)}/search?query=${encodeQueryValue(query)}"
    }

    private fun documentUriString(documentId: String): String {
        return "$CONTENT_URI_PREFIX/document/${encodePathSegment(documentId)}"
    }

    private fun treeDocumentUriString(documentId: String): String {
        return "$CONTENT_URI_PREFIX/tree/${encodePathSegment(documentId)}"
    }

    private fun documentUriUsingTreeString(treeUri: String, documentId: String): String {
        return "$treeUri/document/${encodePathSegment(documentId)}"
    }

    private fun childDocumentsUriString(parentDocumentId: String): String {
        return "$CONTENT_URI_PREFIX/document/${encodePathSegment(parentDocumentId)}/children"
    }

    private fun childDocumentsUriUsingTreeString(treeUri: String, parentDocumentId: String): String {
        return "$treeUri/document/${encodePathSegment(parentDocumentId)}/children"
    }

    private fun encodePathSegment(value: String): String {
        return urlEncode(value).replace("+", "%20")
    }

    private fun encodeQueryValue(value: String): String {
        return urlEncode(value)
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private const val CONTENT_URI_PREFIX = "content://$AUTHORITY"
}

class ProviderChangeNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun notifyDocumentChanged(documentId: String) {
        val resolver = appContext.contentResolver
        val uris = GarlandProviderContract.changedUris(
            documentId = documentId,
            trackedQueries = GarlandProviderContract.loadTrackedSearchQueries(appContext),
        )
        uris.forEach { uri -> resolver.notifyChange(uri, null) }
    }
}
