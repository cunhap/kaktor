package org.kaktor.core

import kotlinx.coroutines.Job
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal actual val actorsRegisteredMap: MutableMap<ActorRef, RegisterInformation<out Any>> = ConcurrentHashMap()
internal actual val actorsMap: MutableMap<String, ActorInformation> = ConcurrentHashMap()
internal actual val actorsPassivationJobs: MutableMap<String, Job> = ConcurrentHashMap()
