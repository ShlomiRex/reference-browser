/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.components

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import mozilla.components.browser.engine.gecko.permission.GeckoSitePermissionsStorage
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.storage.sync.RemoteTabsStorage
import mozilla.components.browser.thumbnails.ThumbnailsMiddleware
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.addons.AddonManager
import mozilla.components.feature.addons.amo.AMOAddonsProvider
import mozilla.components.feature.addons.migration.DefaultSupportedAddonsChecker
import mozilla.components.feature.addons.update.DefaultAddonUpdater
import mozilla.components.feature.customtabs.store.CustomTabsServiceStore
import mozilla.components.feature.downloads.DownloadMiddleware
import mozilla.components.feature.media.MediaSessionFeature
import mozilla.components.feature.media.middleware.RecordingDevicesMiddleware
import mozilla.components.feature.prompts.file.FileUploadsDirCleaner
import mozilla.components.feature.pwa.ManifestStorage
import mozilla.components.feature.pwa.WebAppShortcutManager
import mozilla.components.feature.readerview.ReaderViewMiddleware
import mozilla.components.feature.search.middleware.SearchMiddleware
import mozilla.components.feature.search.region.RegionMiddleware
import mozilla.components.feature.session.HistoryDelegate
import mozilla.components.feature.sitepermissions.OnDiskSitePermissionsStorage
import mozilla.components.feature.webnotifications.WebNotificationFeature
import mozilla.components.lib.dataprotect.SecureAbove22Preferences
import mozilla.components.service.location.LocationService
import mozilla.components.service.sync.logins.SyncableLoginsStorage
import mozilla.components.support.base.worker.Frequency
import org.mozilla.reference.browser.AppRequestInterceptor
import org.mozilla.reference.browser.BrowserActivity
import org.mozilla.reference.browser.EngineProvider
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.R.string.pref_key_remote_debugging
import org.mozilla.reference.browser.R.string.pref_key_tracking_protection_normal
import org.mozilla.reference.browser.R.string.pref_key_tracking_protection_private
import org.mozilla.reference.browser.downloads.DownloadService
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.getPreferenceKey
import org.mozilla.reference.browser.media.MediaSessionService
import org.mozilla.reference.browser.settings.Settings
import java.util.concurrent.TimeUnit

private const val DAY_IN_MINUTES = 24 * 60L

/**
 * Component group for all core browser functionality.
 */
class Core(private val context: Context) {
    /**
     * The browser engine component initialized based on the build
     * configuration (see build variants).
     */
    val engine: Engine by lazy {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val defaultSettings = DefaultSettings(
            requestInterceptor = AppRequestInterceptor(context),
            remoteDebuggingEnabled = false,
            testingModeEnabled = false,
            trackingProtectionPolicy = TrackingProtectionPolicy.recommended(),
            historyTrackingDelegate = HistoryDelegate(lazyHistoryStorage),
            globalPrivacyControlEnabled = prefs.getBoolean(
                context.getPreferenceKey(R.string.pref_key_global_privacy_control),
                false,
            ),
        )
        var engine = EngineProvider.createEngine(context, defaultSettings)
        engine
    }

    /**
     * The [Client] implementation (`concept-fetch`) used for HTTP requests.
     */
    val client: Client by lazy {
        EngineProvider.createClient(context)
    }

    /**
     * The [BrowserStore] holds the global [BrowserState].
     */
    val store by lazy {
        BrowserStore(
            middleware = listOf(
                DownloadMiddleware(context, DownloadService::class.java),
                ThumbnailsMiddleware(thumbnailStorage),
                ReaderViewMiddleware(),
                RegionMiddleware(
                    context,
                    LocationService.default(),
                ),
                SearchMiddleware(context),
                RecordingDevicesMiddleware(context, context.components.notificationsDelegate),
            ) + EngineMiddleware.create(engine),
        ).apply {
            icons.install(engine, this)

            WebNotificationFeature(
                context,
                engine,
                icons,
                R.drawable.ic_notification,
                geckoSitePermissionsStorage,
                BrowserActivity::class.java,
                notificationsDelegate = context.components.notificationsDelegate,
            )

            MediaSessionFeature(context, MediaSessionService::class.java, this).start()
        }
    }

    /**
     * The [CustomTabsServiceStore] holds global custom tabs related data.
     */
    val customTabsStore by lazy { CustomTabsServiceStore() }

    /**
     * The storage component for persisting browser tab sessions.
     */
    val sessionStorage: SessionStorage by lazy {
        SessionStorage(context, engine)
    }

    /**
     * The storage component to persist browsing history (with the exception of
     * private sessions).
     */
    val lazyHistoryStorage = lazy { PlacesHistoryStorage(context) }

    /**
     * A convenience accessor to the [PlacesHistoryStorage].
     */
    val historyStorage by lazy { lazyHistoryStorage.value }

    /**
     * The storage component to persist logins data (username/password) for websites.
     */
    val lazyLoginsStorage = lazy { SyncableLoginsStorage(context, lazySecurePrefs) }

    /**
     * The storage component to sync and persist tabs in a Firefox Sync account.
     */
    val lazyRemoteTabsStorage = lazy { RemoteTabsStorage(context) }

    /**
     * A storage component for persisting thumbnail images of tabs.
     */
    val thumbnailStorage by lazy { ThumbnailStorage(context) }

    /**
     * Component for managing shortcuts (both regular and PWA).
     */
    val shortcutManager by lazy { WebAppShortcutManager(context, client, ManifestStorage(context)) }

    /**
     * A storage component for site permissions.
     */
    val geckoSitePermissionsStorage by lazy {
        val geckoRuntime = EngineProvider.getOrCreateRuntime(context)
        GeckoSitePermissionsStorage(geckoRuntime, OnDiskSitePermissionsStorage(context))
    }

    /**
     * Icons component for loading, caching and processing website icons.
     */
    val icons by lazy { BrowserIcons(context, client) }

    val fileUploadsDirCleaner: FileUploadsDirCleaner by lazy {
        FileUploadsDirCleaner { context.cacheDir }
    }

    private val lazySecurePrefs = lazy { SecureAbove22Preferences(context, KEY_STORAGE_NAME) }

    companion object {
        private const val KEY_STORAGE_NAME = "core_prefs"
    }
}
