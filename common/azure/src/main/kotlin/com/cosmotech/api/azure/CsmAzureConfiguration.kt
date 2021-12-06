// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.api.azure

import com.azure.cosmos.CosmosClient
import com.azure.cosmos.CosmosClientBuilder
import com.azure.identity.ClientSecretCredentialBuilder
import com.azure.security.keyvault.secrets.SecretClient
import com.azure.security.keyvault.secrets.SecretClientBuilder
import com.azure.storage.blob.BlobServiceClient
import com.azure.storage.blob.BlobServiceClientBuilder
import com.azure.storage.blob.batch.BlobBatchClient
import com.azure.storage.blob.batch.BlobBatchClientBuilder
import com.cosmotech.api.config.CsmPlatformProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(name = ["csm.platform.vendor"], havingValue = "azure", matchIfMissing = true)
internal class CsmAzureConfiguration(
    private val cosmosClientBuilder: CosmosClientBuilder,
    private val blobServiceClientBuilder: BlobServiceClientBuilder,
    private val csmPlatformProperties: CsmPlatformProperties,
) {

  @Bean fun cosmosClient(): CosmosClient = cosmosClientBuilder.buildClient()

  @Bean fun storageClient(): BlobServiceClient = blobServiceClientBuilder.buildClient()

  @Bean
  fun secretClient(): SecretClient {
    val azureConf = csmPlatformProperties.azure!!
    return SecretClientBuilder()
        .vaultUrl(azureConf.keyVault.uri)
        .credential(
            ClientSecretCredentialBuilder()
                .tenantId(azureConf.credentials.core.tenantId)
                .clientId(azureConf.credentials.core.clientId)
                .clientSecret(azureConf.credentials.core.clientSecret)
                .build())
        .buildClient()
  }

  @Bean
  fun batchStorageClient(): BlobBatchClient = BlobBatchClientBuilder(storageClient()).buildClient()
}
