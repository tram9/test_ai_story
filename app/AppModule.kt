package com.example.livescore.di

import android.database.sqlite.SQLiteDatabase
import com.example.livescore.data.local.AppDatabase
import com.example.livescore.data.local.sqlite.DatabaseProvider
import com.example.livescore.data.local.sqlite.DirectLeagueDao
import com.example.livescore.data.local.sqlite.DirectTeamDao
import com.example.livescore.data.remote.apiservice.HighlightApi
import com.example.livescore.data.remote.apiservice.HighlightApiImpl
import com.example.livescore.data.remote.apiservice.PredictionApi
import com.example.livescore.data.remote.apiservice.RemoteService
import com.example.livescore.data.remote.apiservice.RemoteServiceImpl
import com.example.livescore.data.remote.factory.HttpClientFactory
import com.example.livescore.data.repository.FixtureNotificationRepositoryImpl
import com.example.livescore.data.repository.FixtureRepositoryImpl
import com.example.livescore.data.repository.HighlightRepositoryImpl
import com.example.livescore.data.repository.LeagueRepositoryImpl
import com.example.livescore.data.repository.MatchRepositoryImpl
import com.example.livescore.data.repository.NotificationSchedulerImpl
import com.example.livescore.data.repository.PredictionRepositoryImpl
import com.example.livescore.data.repository.TeamRepositoryImpl
import com.example.livescore.domain.repository.FixtureNotificationRepository
import com.example.livescore.domain.repository.FixtureRepository
import com.example.livescore.domain.repository.HighlightRepository
import com.example.livescore.domain.repository.LeagueRepository
import com.example.livescore.domain.repository.MatchRepository
import com.example.livescore.domain.repository.NotificationScheduler
import com.example.livescore.domain.repository.PredictionRepository
import com.example.livescore.domain.repository.TeamRepository
import com.example.livescore.presentation.viewmodel.DuckRaceViewModel
import com.example.livescore.presentation.viewmodel.ExploreViewModel
import com.example.livescore.presentation.viewmodel.HighlightViewModel
import com.example.livescore.presentation.viewmodel.HomeLeagueViewModel
import com.example.livescore.presentation.viewmodel.HomeTeamViewModel
import com.example.livescore.presentation.viewmodel.LeagueViewModel
import com.example.livescore.presentation.viewmodel.MainViewModel
import com.example.livescore.presentation.viewmodel.MatchDetailViewModel
import com.example.livescore.presentation.viewmodel.NotificationViewModel
import com.example.livescore.presentation.viewmodel.ObFavouriteViewModel
import com.example.livescore.presentation.viewmodel.PredictionViewModel
import com.example.livescore.presentation.viewmodel.SearchViewModel
import com.example.livescore.presentation.viewmodel.SelectDateViewModel
import com.example.livescore.presentation.viewmodel.TeamViewModel
import com.vio.paywall.di.paywallModule
import com.vio.paywall.domain.repository.PaywallDataProvider
import com.vio.paywall.presentation.factory.PaywallUiFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module

private val cachedAppModules: List<Module> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    listOf(
        viewModelModule,
        presentationCommonModule,
        dataModule,
        networkModule,
        paywallModule
    )
}

fun appModule(): List<Module> = cachedAppModules

fun prewarmKoinModules(): List<Module> = cachedAppModules

val viewModelModule: Module by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    module {
        viewModel { params ->
            DuckRaceViewModel(
                raceSeed = params.get(),
                duck1Name = params.get(),
                duck1Logo = params.get(),
                duck2Name = params.get(),
                duck2Logo = params.get()
            )
        }
        viewModel { SearchViewModel(get(), get()) }
        viewModel { LeagueViewModel(get(), get(), get()) }
        viewModel { TeamViewModel(get(), get(), get(), get()) }
        viewModel { MainViewModel(get(), get(), get(), get<FixtureNotificationRepository>(), get()) }
        viewModel { HomeTeamViewModel(get()) }
        viewModel { HomeLeagueViewModel(get(), get()) }
        viewModel { MatchDetailViewModel(get(), get(), get()) }
        viewModel { SelectDateViewModel() }
        viewModel { NotificationViewModel(get()) }
        viewModel { ObFavouriteViewModel(get(), get()) }
        viewModel { ExploreViewModel(androidContext(), get(), get(), get(), get(), get()) }
        viewModel { PredictionViewModel(androidApplication(), get(), get(), get(), get(), get()) }
        viewModel { HighlightViewModel(get(), get(), get()) }
    }
}

val presentationCommonModule: Module by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    module {
        single<PaywallDataProvider> { AppPaywallDataProvider() }
        single<PaywallUiFactory> { AppPaywallFactory() }

        single<LeagueRepository> { LeagueRepositoryImpl(get(), get()) }
        single<TeamRepository> { TeamRepositoryImpl(androidContext(), get(), get()) }
        single<FixtureRepository> { FixtureRepositoryImpl(get(), get()) }
        single<MatchRepository> { MatchRepositoryImpl(get()) }
        single<FixtureNotificationRepository> {
            FixtureNotificationRepositoryImpl(
                dao = get(),                    // fixtureNotificationDao
                scheduler = get(), // NotificationScheduler
                ioDispatcher = Dispatchers.IO,
            )
        }
        single<NotificationScheduler> { NotificationSchedulerImpl(androidContext()) }
        single<HighlightRepository> {
            HighlightRepositoryImpl(androidContext(), get(), get())
        }

        single<PredictionRepository> {
            PredictionRepositoryImpl(get())
        }
    }
}

val dataModule: Module by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    module {
        single { AppDatabase.getInstance(get()) }
        single { get<AppDatabase>().leagueDao() }
        single { get<AppDatabase>().teamDao() }
        single { get<AppDatabase>().fixtureDao() }
        single { get<AppDatabase>().highlightDao() }
        single { get<AppDatabase>().fixtureNotificationDao() }

        single { DatabaseProvider(androidApplication()) }
        single<SQLiteDatabase> {
            get<DatabaseProvider>().database
        }
        single { get<DatabaseProvider>().databaseTrigger }
        single { { get<DatabaseProvider>().notifyDataChanged() } }
        single {
            DirectTeamDao(
                db = get(),
                ioDispatcher = Dispatchers.IO,
                dbProvider = get(),
            )
        }
        single {
            DirectLeagueDao(
                db = get(),
                ioDispatcher = Dispatchers.IO,
                dbProvider = get()
            )
        }
    }
}

val networkModule: Module by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    module {
        single<HttpClient> {
            HttpClientFactory.createLordEagleClient()
        }

        single<RemoteService> {
            RemoteServiceImpl(get())
        }
        single<HighlightApi> {
            HighlightApiImpl(get())
        }

        single {
            PredictionApi(get())
        }
    }
}