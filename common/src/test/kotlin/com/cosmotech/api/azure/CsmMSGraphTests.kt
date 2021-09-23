// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.api.azure

import com.cosmotech.api.config.CsmPlatformProperties
import com.cosmotech.api.config.CsmPlatformProperties.CsmPlatformAzure.CsmPlatformAzureCredentials
import com.cosmotech.api.config.CsmPlatformProperties.CsmPlatformAzure.CsmPlatformAzureCredentials.CsmPlatformAzureCredentialsCore
import com.microsoft.graph.requests.GraphServiceClient
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import okhttp3.Request
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class CsmMSGraphTests {
  @MockK(relaxed = true) private lateinit var azure: CsmPlatformProperties.CsmPlatformAzure
  @MockK(relaxed = true) private lateinit var csmPlatformProperties: CsmPlatformProperties
  private val CORE_CLIENT_SECRET = "CORE_CLIENT_SECRET"
  private val CUSTOMER_CLIENT_SECRET = "CUSTOMER_CLIENT_SECRET"

  private val TENANTID = "46c67aff-b6b1-4026-bca5-d42e7a958d3b"
  private val CLIENTID = "ee820f5b-847a-4979-8daa-9f4d9635e973"
  private val CLIENTSECRET = System.getenv(CORE_CLIENT_SECRET)
  private val USER_TEST_OID = "795598d6-4677-4997-bfbf-c8eb2b5faf48"
  private lateinit var csmMSGraph: CsmMSGraph

  @BeforeTest
  fun setUp() {
    this.csmMSGraph = CsmMSGraph()
    MockKAnnotations.init(this)

    every { azure.credentials } returns
        CsmPlatformAzureCredentials(
            core =
                CsmPlatformAzureCredentialsCore(
                    tenantId = "46c67aff-b6b1-4026-bca5-d42e7a958d3b",
                    clientId = "ee820f5b-847a-4979-8daa-9f4d9635e973",
                    clientSecret = System.getenv(CORE_CLIENT_SECRET),
                    aadPodIdBinding = "phoenixdev-pod-identity",
                ),
            customer =
                CsmPlatformAzureCredentials.CsmPlatformAzureCredentialsCustomer(
                    tenantId = "46c67aff-b6b1-4026-bca5-d42e7a958d3b",
                    clientId = "5c330f43-1199-454c-8057-c49dc594466c",
                    clientSecret = System.getenv(CUSTOMER_CLIENT_SECRET)))
    every { csmPlatformProperties.azure } returns azure
  }

  @Test
  fun `Client not null`() {
    val client = this.getClient()
    assertNotNull(client)
  }

  @Test
  fun `Find client User Test`() {
    val client = this.getClient()
    val user = this.csmMSGraph.findUserByOid(client, USER_TEST_OID)
    assertEquals(user?.id, USER_TEST_OID)
  }

  private fun getClient(): GraphServiceClient<Request> {
    return this.csmMSGraph.getClient(TENANTID, CLIENTID, CLIENTSECRET)
  }
}
