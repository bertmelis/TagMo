/*
 * ====================================================================
 * Copyright (C) 2022 AbandonedCart @ TagMo
 * ====================================================================
 */
package com.hiddenramblings.tagmo.amiibo.tagdata

import com.hiddenramblings.tagmo.nfctech.TagArray
import java.util.*

class AppDataSplatoon3(appData: ByteArray?) : AppData(appData!!) {

    fun checkSaveData(): Boolean {
        return SaveDataSplatoon(appData.array()).checkSaveData()
    }

    fun injectSaveData() {
        appData.put(SaveDataSplatoon(appData.array()).injectSaveData())
    }
}