// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask

dependencies {
  api(projects.cosmotechApiCommonParent.cosmotechApiCommon)
  api(projects.cosmotechApiCommonParent.cosmotechApiCommonAzure)
}

sourceSets {
  main { java.srcDirs("$buildDir/generated-sources/openapi/src/main/kotlin") }
  test { java.srcDirs("$buildDir/generated-sources/openapi/src/test/kotlin") }
}

tasks.getByName<ValidateTask>("openApiValidate") {
  inputSpec.set("${projectDir}/src/main/openapi/connector.yaml")
}

tasks.getByName<GenerateTask>("openApiGenerate") {
  inputSpec.set("${projectDir}/src/main/openapi/connector.yaml")
  outputDir.set("$buildDir/generated-sources/openapi")
  generatorName.set("kotlin-spring")
  apiPackage.set("com.cosmotech.connector.api")
  modelPackage.set("com.cosmotech.connector.domain")
  globalProperties.set(
      mapOf(
          "apiDocs" to "true",
          // Excluded because the OpenAPI Generator generates test classes that expect the
          // Service Implementation to be present in the 'apiPackage' package,
          // which is not the case when serviceInterface is true.
          // We will write our own tests instead.
          "apiTests" to "false"))
  additionalProperties.set(
      mapOf(
          "title" to "Cosmo Tech Connector Manager API",
          "basePackage" to "com.cosmotech",
          "configPackage" to "com.cosmotech.connector.config",
          "enumPropertyNaming" to "original",
          "exceptionHandler" to false,
          "serviceInterface" to true,
          "swaggerAnnotations" to false,
          "useTags" to true))
}
