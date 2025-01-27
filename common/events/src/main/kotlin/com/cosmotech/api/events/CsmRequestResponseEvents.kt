// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.api.events

sealed class CsmRequestResponseEvent<T>(publisher: Any) : CsmEvent(publisher) {
  var response: T? = null
}

class WorkflowStatusRequest(
    publisher: Any,
    val workflowId: String,
    val workflowName: String,
) : CsmRequestResponseEvent<String>(publisher)

class ScenarioDataDownloadRequest(
    publisher: Any,
    val jobId: String,
    val organizationId: String,
    val workspaceId: String,
    val scenarioId: String
) : CsmRequestResponseEvent<Map<String, Any>>(publisher)

class ScenarioDataDownloadJobInfoRequest(
    publisher: Any,
    val jobId: String,
    val organizationId: String,
) : CsmRequestResponseEvent<Pair<String?, String>>(publisher)
