package com.laserdroid.service

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder

class MachineServiceConnection(
    private val onConnected:
        (MachineService) -> Unit,

    private val onDisconnected:
        () -> Unit
) : ServiceConnection {

    override fun onServiceConnected(
        name: ComponentName?,
        serviceBinder: IBinder?
    ) {

        val binder =
            serviceBinder as?
                    MachineService.LocalBinder

        val service =
            binder?.getService()

        if (
            service != null
        ) {

            onConnected(
                service
            )
        }
    }

    override fun onServiceDisconnected(
        name: ComponentName?
    ) {

        onDisconnected()
    }
}