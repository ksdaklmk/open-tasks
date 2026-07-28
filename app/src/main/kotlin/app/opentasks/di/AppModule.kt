package app.opentasks.di

import android.content.Context
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.domain.DefaultInsightsEngine
import app.opentasks.core.domain.InsightsEngine
import app.opentasks.core.domain.VaultRepository
import app.opentasks.InsightsTimeProvider
import app.opentasks.SystemInsightsTimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideInsightsTimeProvider(): InsightsTimeProvider = SystemInsightsTimeProvider()

    @Provides
    @Singleton
    fun provideInsightsEngine(): InsightsEngine = DefaultInsightsEngine()

    @Provides
    @Singleton
    fun provideVaultRepository(
        @ApplicationContext context: Context,
    ): VaultRepository = LocalVaultRepositoryFactory.create(context)
}
