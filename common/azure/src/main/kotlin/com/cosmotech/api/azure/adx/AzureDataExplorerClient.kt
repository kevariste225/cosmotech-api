// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.api.azure.adx

import com.cosmotech.api.config.CsmPlatformProperties
import com.cosmotech.api.scenariorun.DataIngestionState
import com.cosmotech.api.scenariorun.PostProcessingDataIngestionStateProvider
import com.microsoft.azure.kusto.data.Client
import com.microsoft.azure.kusto.data.ClientImpl
import com.microsoft.azure.kusto.data.ClientRequestProperties
import com.microsoft.azure.kusto.data.auth.ConnectionStringBuilder
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

private const val REQUEST_TIMEOUT_SECONDS = 30L

@Service
@ConditionalOnProperty(name = ["csm.platform.vendor"], havingValue = "azure", matchIfMissing = true)
internal class AzureDataExplorerClient(val csmPlatformProperties: CsmPlatformProperties) :
    PostProcessingDataIngestionStateProvider {

  private val logger = LoggerFactory.getLogger(AzureDataExplorerClient::class.java)

  private lateinit var dataIngestionStateIfNoControlPlaneInfoButProbeMeasuresData:
      DataIngestionState

  // TODO Make this contribute to the global application Health

  private val kustoClient: Client by lazy {
    val csmPlatformAzure = csmPlatformProperties.azure!!
    // TODO Investigate whether we need to use core or customer creds
    val csmPlatformAzureCredentials = csmPlatformAzure.credentials.core
    ClientImpl(
        ConnectionStringBuilder.createWithAadApplicationCredentials(
            csmPlatformAzure.dataWarehouseCluster.baseUri,
            csmPlatformAzureCredentials.clientId,
            csmPlatformAzureCredentials.clientSecret,
            csmPlatformAzureCredentials.tenantId))
  }

  @PostConstruct
  fun init() {
    this.dataIngestionStateIfNoControlPlaneInfoButProbeMeasuresData =
        DataIngestionState.valueOf(
            csmPlatformProperties.dataIngestionState.stateIfNoControlPlaneInfoButProbeMeasuresData)
  }

  override fun getStateFor(
      organizationId: String,
      workspaceKey: String,
      scenarioRunId: String,
      csmSimulationRun: String
  ): DataIngestionState? {
    logger.trace("getStateFor($organizationId,$workspaceKey,$scenarioRunId,$csmSimulationRun)")

    val sentMessagesTotal = querySentMessagesTotal(organizationId, workspaceKey, csmSimulationRun)
    val probesMeasuresCount =
        queryProbesMeasuresCount(organizationId, workspaceKey, csmSimulationRun)

    logger.debug(
        "For scenario run $scenarioRunId (csmSimulationRun=" +
            csmSimulationRun +
            ": (sentMessagesTotal,probesMeasuresCount)=($sentMessagesTotal, $probesMeasuresCount)")
    return if (sentMessagesTotal == null) {
      if (probesMeasuresCount > 0) {
        this.dataIngestionStateIfNoControlPlaneInfoButProbeMeasuresData
      } else if (csmPlatformProperties
          .dataIngestionState
          .exceptionIfNoControlPlaneInfoAndNoProbeMeasuresData) {
        throw UnsupportedOperationException(
            "Case not handled: probesMeasuresCount=0 and sentMessagesTotal=NULL. " +
                "Simulation run $csmSimulationRun probably has no consumers.")
      } else {
        // For backward compatibility purposes
        DataIngestionState.Successful
      }
    } else if (probesMeasuresCount < sentMessagesTotal) {
      DataIngestionState.InProgress
    } else {
      DataIngestionState.Successful
    }
  }

  private fun queryProbesMeasuresCount(
      organizationId: String,
      workspaceKey: String,
      csmSimulationRun: String
  ): Long {
    val requestProperties =
        ClientRequestProperties().apply {
          timeoutInMilliSec = TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS)
        }

    val probesMeasuresCountQueryPrimaryResults =
        this.kustoClient.execute(
                getDatabaseName(organizationId, workspaceKey),
                """
                ProbesMeasures
                | where SimulationRun == '${csmSimulationRun}'
                | count
            """,
                requestProperties)
            .primaryResults
    if (!probesMeasuresCountQueryPrimaryResults.next()) {
      throw IllegalStateException("Missing ProbesMeasures table")
    }
    return probesMeasuresCountQueryPrimaryResults.getLongObject("Count")!!
  }

  private fun querySentMessagesTotal(
      organizationId: String,
      workspaceKey: String,
      csmSimulationRun: String
  ): Long? {
    val requestProperties =
        ClientRequestProperties().apply {
          timeoutInMilliSec = TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS)
        }

    val sentMessagesTotalQueryPrimaryResults =
        this.kustoClient.execute(
                getDatabaseName(organizationId, workspaceKey),
                """
                SimulationTotalFacts
                | where SimulationId == '${csmSimulationRun}'
                | project SentMessagesTotal
            """,
                requestProperties)
            .primaryResults

    val sentMessagesTotalRowCount = sentMessagesTotalQueryPrimaryResults.count()
    if (sentMessagesTotalRowCount > 1) {
      throw IllegalStateException(
          "Unexpected number of rows in SimulationTotalFacts ADX Table for SimulationId=" +
              csmSimulationRun +
              ". Expected at most 1, but got " +
              sentMessagesTotalRowCount)
    }
    return if (sentMessagesTotalQueryPrimaryResults.next()) {
      sentMessagesTotalQueryPrimaryResults.getLongObject("SentMessagesTotal")
    } else {
      // No row
      null
    }
  }
}

private fun getDatabaseName(organizationId: String, workspaceKey: String) =
    "${organizationId}-${workspaceKey}"
