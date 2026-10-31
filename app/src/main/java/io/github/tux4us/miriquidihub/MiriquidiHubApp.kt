package io.github.tux4us.miriquidihub

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class MiriquidiHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Die App verwendet ausschließlich das Dark-/Glass-Design.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Gespeicherte App-Sprache anwenden. Ohne diesen Aufruf würde die
        // Auswahl erst nach dem nächsten manuellen Umschalten wirksam, da
        // AppCompatDelegate.setApplicationLocales() sonst nur beim Toggle
        // selbst gesetzt wird.
        LanguageHelper.applySavedLanguage(this)
    }
}
