package tv.hudl.fanplayer.data

import tv.hudl.fanplayer.domain.HudlEvent
import tv.hudl.fanplayer.domain.OrganizationReference

/** Boundary for the future Hudl integration. Keep transport details out of the UI. */
interface HudlEventService {
    /** Blocking by design for this dependency-free proof of concept; call off the UI thread. */
    fun fetchEvents(organization: OrganizationReference): List<HudlEvent>
}
