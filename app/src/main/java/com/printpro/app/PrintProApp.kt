package com.printpro.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PrintProApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
