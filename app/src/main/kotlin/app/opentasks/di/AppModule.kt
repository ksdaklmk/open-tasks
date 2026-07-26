package app.opentasks.di

import android.content.Context
import app.opentasks.core.data.LocalVaultRepositoryFactory
import app.opentasks.core.domain.VaultRepository
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
    fun provideVaultRepository(
        @ApplicationContext context: Context,
    ): VaultRepository = LocalVaultRepositoryFactory.create(context)
}
