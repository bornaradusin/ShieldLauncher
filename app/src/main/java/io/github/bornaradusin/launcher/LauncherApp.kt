package io.github.bornaradusin.launcher

import android.app.Application
import io.github.bornaradusin.launcher.apps.Apps
import io.github.bornaradusin.launcher.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The launcher's parts, made once and shared by the screens, the phone page and the listener. */
class LauncherApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var store: Store
        private set
    lateinit var apps: Apps
        private set
    lateinit var updater: Updater
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        apps = Apps(this, scope)
        updater = Updater(this, scope)
        apps.start()
        updater.start()
    }

    companion object {
        lateinit var instance: LauncherApp
            private set
    }
}
