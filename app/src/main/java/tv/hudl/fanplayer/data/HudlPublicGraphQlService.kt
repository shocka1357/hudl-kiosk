package tv.hudl.fanplayer.data

import org.json.JSONArray
import org.json.JSONObject
import tv.hudl.fanplayer.domain.HudlEvent
import tv.hudl.fanplayer.domain.HudlEventStatus
import tv.hudl.fanplayer.domain.OrganizationReference
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dependency-free adapter for the public Hudl Fan GraphQL surface observed in the browser client.
 * Keep all Hudl-specific transport details here.
 *
 * The current public flow is two-step:
 * 1. resolve the user's numeric organization ID to Hudl's encoded GraphQL school ID;
 * 2. pass that encoded ID to fanBroadcasts.
 *
 * This is intentionally not a playback implementation. embedCodeSrc is an iframe page URL, not
 * proof of a directly playable HLS/DASH manifest.
 */
class HudlPublicGraphQlService(
    private val endpoint: String = "https://www.hudl.com/api/public/graphql/query"
) : HudlEventService {

    override fun fetchEvents(organization: OrganizationReference): List<HudlEvent> {
        val school = resolveSchool(organization.id)
        val variables = JSONObject().put(
            "input",
            JSONObject()
                .put("first", 100)
                .put("sortType", "BROADCAST_DATE")
                .put("sortByAscending", false)
                .put("broadcastStatusFilter", "ALL")
                // Despite the plural field name, the public schema currently accepts one ID.
                .put("schoolIds", school.graphQlId)
        )

        val root = request(
            operationName = "Web_Fan_GetFanBroadcasts_r1",
            query = FAN_BROADCASTS_QUERY,
            variables = variables
        )
        val edges = root.getJSONObject("data")
            .getJSONObject("fanBroadcasts")
            .optJSONArray("edges") ?: JSONArray()

        return buildList(edges.length()) {
            for (index in 0 until edges.length()) {
                val node = edges.getJSONObject(index).getJSONObject("node")
                add(
                    HudlEvent(
                        id = node.optString("id"),
                        broadcastId = node.optNullableString("broadcastId"),
                        organizationName = school.fullName,
                        title = node.optString("title", "Untitled Hudl event"),
                        sport = null,
                        startTimeUtc = node.optNullableString("broadcastDateUtc"),
                        status = HudlEventStatus.fromHudlStatus(node.optString("status")),
                        thumbnailUrl = node.optNullableString("largeThumbnail"),
                        playbackPageUrl = node.optNullableString("embedCodeSrc")
                    )
                )
            }
        }
    }

    private fun resolveSchool(numericId: String): ResolvedSchool {
        val variables = JSONObject().put("schoolIds", JSONArray().put(numericId))
        val root = request(
            operationName = "Web_Fan_GetSchools_r1",
            query = SCHOOLS_QUERY,
            variables = variables
        )
        val schools = root.getJSONObject("data").optJSONArray("schools")
            ?: throw IOException("Hudl returned no organizations")
        if (schools.length() == 0) throw IOException("Hudl organization was not found")
        val school = schools.getJSONObject(0)
        return ResolvedSchool(
            graphQlId = school.getString("id"),
            fullName = school.optString("fullName").takeIf { it.isNotBlank() }
        )
    }

    private fun request(
        operationName: String,
        query: String,
        variables: JSONObject
    ): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://fan.hudl.com")
            setRequestProperty("Referer", "https://fan.hudl.com/")
        }

        try {
            val body = JSONObject()
                .put("operationName", operationName)
                .put("variables", variables)
                .put("query", query)
                .toString()
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val response = (if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (response.isBlank()) throw IOException("Hudl returned an empty response")

            val root = JSONObject(response)
            val errors = root.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                throw IOException("Hudl GraphQL error: ${errors.getJSONObject(0).optString("message")}")
            }
            return root
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private companion object {
        const val SCHOOLS_QUERY = """
            query Web_Fan_GetSchools_r1(${ '$' }schoolIds: [String], ${ '$' }graphQLSchoolIds: [ID!]) {
              schools(schoolIds: ${ '$' }schoolIds, graphQLSchoolIds: ${ '$' }graphQLSchoolIds) {
                id
                fullName
              }
            }
        """

        const val FAN_BROADCASTS_QUERY = """
            query Web_Fan_GetFanBroadcasts_r1(${ '$' }input: GetFanBroadcastsPaginatedInput!) {
              fanBroadcasts(input: ${ '$' }input) {
                edges {
                  node {
                    id
                    broadcastId
                    title
                    status
                    schoolId
                    scheduleEntryId
                    broadcastDateUtc
                    available
                    embedCodeSrc
                    largeThumbnail
                    duration
                    requireLogin
                  }
                }
              }
            }
        """
    }

    private data class ResolvedSchool(val graphQlId: String, val fullName: String?)
}
