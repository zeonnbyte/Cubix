package com.cubix.launcher

import android.content.res.AssetManager
import android.os.Bundle
import com.mojang.minecraftpe.MainActivity

/** Subclass dari MainActivity buatan sendiri (modul :minecraft), bukan kelas asli Mojang yang dilindungi PairIP. */
class GameActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        for ((k, v) in McLoader.firebaseExtras(this)) intent.putExtra(k, v)
        super.onCreate(savedInstanceState)
    }

    override fun getAssets(): AssetManager = McLoader.gameAssets ?: super.getAssets()
}
