package org.kaktor.core

import kotlinx.coroutines.Job

internal actual val actorsRegisteredMap: MutableMap<ActorRef, RegisterInformation<out Any>> = mutableMapOf()
internal actual val actorsMap: MutableMap<String, ActorInformation> = mutableMapOf()
internal actual val actorsPassivationJobs: MutableMap<String, Job> = mutableMapOf()
