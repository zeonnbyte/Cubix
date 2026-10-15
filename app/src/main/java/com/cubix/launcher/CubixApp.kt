package com.cubix.launcher

import android.app.Application
import android.content.res.AssetManager

class CubixApp : Application() {
    // MainActivity membaca asset lewat applicationContext.getAssets()
    override fun getAssets(): AssetManager = McLoader.gameAssets ?: super.getAssets()
}
