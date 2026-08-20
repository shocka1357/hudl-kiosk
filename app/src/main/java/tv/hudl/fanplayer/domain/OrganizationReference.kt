package tv.hudl.fanplayer.domain

/** Stable input for a future Hudl client; no network behavior belongs here. */
data class OrganizationReference(val id: String) {
    companion object {
        fun parse(value: String): OrganizationReference? {
            val input = value.trim()
            if (input.isEmpty()) return null

            val id = input.toLongOrNull()?.toString()
                ?: Regex("(?:^|/)(?:team|organization|orgs?)/([A-Za-z0-9_-]+)(?:/|$)")
                    .find(input)?.groupValues?.getOrNull(1)
                ?: return null
            return OrganizationReference(id)
        }
    }
}
