package tv.hudl.fanplayer.data

import tv.hudl.fanplayer.domain.HudlEvent
import tv.hudl.fanplayer.domain.OrganizationReference

interface HudlEventRepository {
    fun eventsFor(organization: OrganizationReference): List<HudlEvent>
}

/**
 * Offline source used until the real Hudl behavior is understood.
 * TODO: replace with a client/service backed by documented or authorized APIs.
 * TODO: add authentication, pagination, retries, and response mapping at that boundary.
 */
class FakeHudlEventRepository : HudlEventRepository {
    override fun eventsFor(organization: OrganizationReference): List<HudlEvent> = listOf(
        HudlEvent(
            id = "demo-1",
            broadcastId = null,
            organizationName = null,
            title = "Next fan event",
            sport = null,
            startTimeUtc = null,
            status = tv.hudl.fanplayer.domain.HudlEventStatus.UPCOMING,
            thumbnailUrl = null,
            playbackPageUrl = null
        )
    )
}
