package org.salutemesh

import android.app.Application

class SaluteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MeshSdkInit.init(this)
    }
}
