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
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

private const val REQUEST_TIMEOUT_SECONDS = 30L

@Service("csmADX")
@ConditionalOnProperty(name = ["csm.platform.vendor"], havingValue = "azure", matchIfMissing = true)
internal class AzureDataExplorerClient(csmPlatformProperties: CsmPlatformProperties) :
    HealthIndicator, PostProcessingDataIngestionStateProvider {

  private val logger = LoggerFactory.getLogger(AzureDataExplorerClient::class.java)

  private val baseUri = csmPlatformProperties.azure!!.dataWarehouseCluster.baseUri

  private val dataIngestionStateIfNoControlPlaneInfoButProbeMeasuresData =
      DataIngestionState.valueOf(
          csmPlatformProperties.dataIngestionState.stateIfNoControlPlaneInfoButProbeMeasuresData)

  private val dataIngestionStateExceptionIfNoControlPlaneInfoAndNoProbeMeasuresData =
      csmPlatformProperties.dataIngestionState.exceptionIfNoControlPlaneInfoAndNoProbeMeasuresData

  private val kustoClient: Client by lazy {
    val csmPlatformAzure = csmPlatformProperties.azure!!
    // TODO Investigate whether we need to use core or customer creds
    val csmPlatformAzureCredentials = csmPlatformAzure.credentials.core
    ClientImpl(
        ConnectionStringBuilder.createWithAadApplicationCredentials(
            baseUri,
            csmPlatformAzureCredentials.clientId,
            csmPlatformAzureCredentials.clientSecret,
            csmPlatformAzureCredentials.tenantId))
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
        "Scenario run {} (csmSimulationRun={}): (sentMessagesTotal,probesMeasuresCount)=(" +
            "$sentMessagesTotal,$probesMeasuresCount)",
        scenarioRunId,
        csmSimulationRun)
    return if (sentMessagesTotal == null) {
      logger.debug(
          "Scenario run {} (csmSimulationRun={}) produced {} measures, " +
              "but no data found in SimulationTotalFacts control plane table",
          scenarioRunId,
          csmSimulationRun,
          probesMeasuresCount)
      if (probesMeasuresCount > 0) {
        this.dataIngestionStateIfNoControlPlaneInfoButProbeMeasuresData
      } else if (this.dataIngestionStateExceptionIfNoControlPlaneInfoAndNoProbeMeasuresData) {
        throw UnsupportedOperationException(
            "Case not handled: probesMeasuresCount=0 and sentMessagesTotal=NULL. " +
                "Scenario run $scenarioRunId (csmSimulationRun=$csmSimulationRun) " +
                "probably ran no AMQP consumers. " +
                "To mitigate this, either make sure to build your Simulator using " +
                "a version of SDK >= 8.5 or configure the " +
                "'csm.platform.data-ingestion-state.exception-if-no-control-plane-info-" +
                "and-no-probe-measures-data' property flag for this API")
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
    val probesMeasuresCountQueryPrimaryResults =
        this.kustoClient.execute(
                getDatabaseName(organizationId, workspaceKey),
                """
                ProbesMeasures
                | where SimulationRun == '${csmSimulationRun}'
                | count
            """,
                ClientRequestProperties().apply {
                  timeoutInMilliSec = TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS)
                })
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
    val sentMessagesTotalQueryPrimaryResults =
        this.kustoClient.execute(
                getDatabaseName(organizationId, workspaceKey),
                """
                SimulationTotalFacts
                | where SimulationId == '${csmSimulationRun}'
                | project SentMessagesTotal
            """,
                ClientRequestProperties().apply {
                  timeoutInMilliSec = TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS)
                })
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

  override fun health(): Health {
    val healthBuilder =
        try {
          val diagnosticsResult =
              this.kustoClient.execute(
                      """
                      .show diagnostics
                      | project IsHealthy
                  """.trimIndent())
                  .primaryResults
          if (!diagnosticsResult.next()) {
            throw IllegalStateException(
                "Could not determine cluster health. " +
                    "Diagnostics query returned no result at all.")
          }
          val isHealthyResult = diagnosticsResult.getIntegerObject("IsHealthy")
          if (isHealthyResult != 1) {
            throw IllegalStateException("Unhealthy cluster. isHealthyResult=$isHealthyResult")
          }
          Health.up()
        } catch (exception: Exception) {
          logger.debug("Error in health-check: {}", exception.message, exception)
          Health.down(exception)
        }
    return healthBuilder.withDetail("baseUri", baseUri).build()
  }
}

private fun getDatabaseName(organizationId: String, workspaceKey: String) =
    "${organizationId}-${workspaceKey}"
