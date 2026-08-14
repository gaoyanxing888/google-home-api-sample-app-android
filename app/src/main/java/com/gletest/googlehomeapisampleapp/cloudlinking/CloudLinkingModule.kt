package com.gletest.googlehomeapisampleapp.cloudlinking

import com.gletest.googlehomeapisampleapp.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/** Hilt module to provide cloud linking dependencies. */
@Module
@InstallIn(ViewModelComponent::class)
object CloudLinkingModule {

  /** Provides the [CloudLinkingConfig] using the playground client ID from [BuildConfig]. */
  @Provides
  fun provideCloudLinkingConfig(): CloudLinkingConfig {
    return CloudLinkingConfig(clientId = BuildConfig.PLAYGROUND_OAUTH_CLIENT_ID)
  }
}
