package org.blackoutrelay.app

import android.app.Application
import org.blackoutrelay.crypto.Crypto

/** Installs the Android crypto backend before anything touches `Crypto`. */
class RelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crypto.backend = AndroidBackend()
    }
}
