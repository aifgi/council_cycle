package uk.co.councilcycle.publisher

import uk.co.councilcycle.model.Decision

interface BlueskyPublisher {
    suspend fun publish(decision: Decision)
    suspend fun publishAll(decisions: List<Decision>)
}
