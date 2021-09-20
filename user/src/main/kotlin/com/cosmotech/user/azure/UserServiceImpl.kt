// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.user.azure

import com.azure.cosmos.models.CosmosContainerProperties
import com.azure.identity.ClientSecretCredential
import com.azure.identity.ClientSecretCredentialBuilder
import com.cosmotech.api.azure.AbstractCosmosBackedService
import com.cosmotech.api.azure.findAll
import com.cosmotech.api.events.OrganizationRegistered
import com.cosmotech.api.events.OrganizationUnregistered
import com.cosmotech.api.events.UserAddedToOrganization
import com.cosmotech.api.events.UserRegistered
import com.cosmotech.api.events.UserRemovedFromOrganization
import com.cosmotech.api.events.UserUnregistered
import com.cosmotech.api.utils.changed
import com.cosmotech.user.api.UserApiService
import com.cosmotech.user.domain.User
import com.cosmotech.user.domain.UserOrganization
import com.microsoft.graph.authentication.TokenCredentialAuthProvider
import com.microsoft.graph.requests.GraphServiceClient
import javax.annotation.PostConstruct
import okhttp3.Request
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["csm.platform.vendor"], havingValue = "azure", matchIfMissing = true)
@Suppress("TooManyFunctions")
internal class UserServiceImpl : AbstractCosmosBackedService(), UserApiService {

  private lateinit var coreUserContainer: String

  @PostConstruct
  fun initService() {
    this.coreUserContainer = csmPlatformProperties.azure!!.cosmos.coreDatabase.users.container
    cosmosCoreDatabase.createContainerIfNotExists(
        CosmosContainerProperties(coreUserContainer, "/id"))
  }

  override fun authorizeUser() {
    TODO("Not yet implemented")
  }

  override fun findAllUsers() = cosmosTemplate.findAll<User>(coreUserContainer)

  override fun findUserById(userId: String): User {
    // cosmosTemplate.findByIdOrThrow(coreUserContainer, userId)
    val clientId = "f6fbd519-9a53-4c6b-aabb-4919bb2d11be"
    val clientSecret = "7Xc7Q~x4HSounXApnF8B2qiIgZseQy4XHG-.G"
    val tenant = "e413b834-8be8-4822-a370-be619545cb49"
    val scopes = mutableListOf("https://graph.microsoft.com/.default")
    val clientSecretCredential: ClientSecretCredential =
        ClientSecretCredentialBuilder()
            .clientId(clientId)
            .clientSecret(clientSecret)
            .tenantId(tenant)
            .build()

    val tokenCredentialAuthProvider = TokenCredentialAuthProvider(scopes, clientSecretCredential)

    val graphClient: GraphServiceClient<Request> =
        GraphServiceClient.builder()
            .authenticationProvider(tokenCredentialAuthProvider)
            .buildClient()

    // var vcrId = "3a869905-e9f5-4851-a7a9-3079aad49dff"
    var vcrMail = "vincent.carluer@cosmotech.com"
    val msUser = graphClient.users(vcrMail).buildRequest().get()
    return User(id = msUser?.id, name = msUser?.displayName)
  }

  override fun getCurrentUser(): User {
    val principal = SecurityContextHolder.getContext().authentication

    logger.debug(
        "Principal (isAuthenticated={}) : '{}' - authorities={}",
        principal.isAuthenticated,
        principal.name,
        principal.authorities)
    return User(name = principal.name, platformRoles = listOf())
  }

  override fun getOrganizationCurrentUser(organizationId: String): User {
    TODO("Not yet implemented")
  }

  override fun getWorkspaceCurrentUser(organizationId: String, workspaceId: String): User {
    TODO("Not yet implemented")
  }

  override fun registerUser(user: User): User {
    if (user.name.isNullOrBlank()) {
      throw IllegalArgumentException("User name must not be null or blank")
    }
    val userRegistered =
        cosmosTemplate.insert(coreUserContainer, user.copy(id = idGenerator.generate("user")))
    val userId =
        userRegistered.id
            ?: throw IllegalStateException(
                "No ID returned for organization registered: $userRegistered")
    this.eventPublisher.publishEvent(UserRegistered(this, userId))
    return userRegistered
  }

  override fun unregisterUser(userId: String) {
    cosmosTemplate.deleteEntity(coreUserContainer, findUserById(userId))
    this.eventPublisher.publishEvent(UserUnregistered(this, userId))
  }

  override fun updateUser(userId: String, user: User): User {
    val existingUser = findUserById(userId)
    var hasChanged = false
    if (user.name != null && user.changed(existingUser) { name }) {
      existingUser.name = user.name
      hasChanged = true
    }
    if (user.platformRoles != null &&
        user.platformRoles?.toSet() != existingUser.platformRoles?.toSet()) {
      // A list preserves the order, but here we actually do not care that much about the order
      existingUser.platformRoles = user.platformRoles
      hasChanged = true
    }
    // Changing the list of Organizations a User is member of can be done via the
    // '/organizations/:id/users' endpoint
    return if (hasChanged) {
      cosmosTemplate.upsertAndReturnEntity(coreUserContainer, existingUser)
    } else {
      existingUser
    }
  }

  @EventListener(OrganizationRegistered::class)
  fun onOrganizationRegistered(organizationRegistered: OrganizationRegistered) {
    cosmosCoreDatabase.createContainerIfNotExists(
        CosmosContainerProperties("${organizationRegistered.organizationId}_user-data", "/ownerId"))
  }

  @EventListener(OrganizationUnregistered::class)
  @Async("csm-in-process-event-executor")
  fun onOrganizationUnregistered(organizationUnregistered: OrganizationUnregistered) {
    cosmosTemplate.deleteContainer("${organizationUnregistered.organizationId}_user-data")
    // TODO Remove organization from all users that reference it
  }

  @EventListener(UserAddedToOrganization::class)
  @Async("csm-in-process-event-executor")
  fun onUserAddedToOrganization(userAddedToOrganization: UserAddedToOrganization) {
    val user = this.findUserById(userAddedToOrganization.userId)
    val organizationMap =
        user.organizations?.associateBy { it.id }?.toMutableMap() ?: mutableMapOf()
    organizationMap[userAddedToOrganization.organizationId] =
        UserOrganization(
            id = userAddedToOrganization.organizationId,
            name = userAddedToOrganization.organizationName,
            roles = userAddedToOrganization.roles)
    user.organizations = organizationMap.values.toList()
    cosmosTemplate.upsert(coreUserContainer, user)
  }

  @EventListener(UserRemovedFromOrganization::class)
  @Async("csm-in-process-event-executor")
  fun onUserUserRemovedFromOrganization(userRemovedFromOrganization: UserRemovedFromOrganization) {
    val user = this.findUserById(userRemovedFromOrganization.userId)
    val organizationMap =
        user.organizations?.associateBy { it.id }?.toMutableMap() ?: mutableMapOf()
    organizationMap.remove(userRemovedFromOrganization.organizationId)
    user.organizations = organizationMap.values.toList()
    cosmosTemplate.upsert(coreUserContainer, user)
  }

  override fun testPlatform(): kotlin.String {
    return "TEST OK. Welcome to the Cosmo Tech Platform"
  }
}
