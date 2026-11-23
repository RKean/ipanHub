package com.cartube.ytauto

import android.app.Application
import com.cartube.ytauto.queue.QueueController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe

class CarTubeApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        QueueController.init(this)
        appScope.launch(Dispatchers.IO) {
            NewPipe.init(NewPipeDownloader.getInstance())
        }
    }
}