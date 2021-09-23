// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.api.azure

import com.azure.identity.ClientSecretCredential
import com.azure.identity.ClientSecretCredentialBuilder
import com.microsoft.graph.authentication.TokenCredentialAuthProvider
import com.microsoft.graph.requests.GraphServiceClient
import com.microsoft.graph.models.User
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CsmMSGraph {
  private val logger = LoggerFactory.getLogger(CsmMSGraph::class.java)
  private val MSGRAPH_SCOPE = "https://graph.microsoft.com/.default"

  fun getClient(tenantId: String, clientId: String, clientSecret: String): GraphServiceClient<Request> {
    logger.debug("Creating ClientSecretCredential")
    val scopes = mutableListOf(MSGRAPH_SCOPE)
    val clientSecretCredential: ClientSecretCredential =
        ClientSecretCredentialBuilder()
            .tenantId(tenantId)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .build()

    logger.debug("Configuring TokenCredentialAuthProvider")
    val tokenCredentialAuthProvider = TokenCredentialAuthProvider(scopes, clientSecretCredential)

    logger.debug("Creating Graph client")
    val graphClient: GraphServiceClient<Request> =
        GraphServiceClient.builder()
            .authenticationProvider(tokenCredentialAuthProvider)
            .buildClient()
    return graphClient
  }

  fun findUserByOid(client: GraphServiceClient<Request>, userOid: String): User? {
    return client.users(userOid).buildRequest().get()
  }
}
