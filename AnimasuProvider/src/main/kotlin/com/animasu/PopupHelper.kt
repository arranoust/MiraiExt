package com.animasu

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

object PopupHelper {

    private const val TAG = "PopupHelper"
    private const val PREFS_NAME = "MiraiExtPrefs"
    private const val KEY_SHOWN_POPUP = "popup_animasu_v1"
    private const val GITHUB_URL = "https://github.com/arranoust/MiraiExt-CloudStream"

    fun showPopupIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (prefs.getBoolean(KEY_SHOWN_POPUP, false)) {
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                val activity = context as? Activity ?: return@post

                prefs.edit().putBoolean(KEY_SHOWN_POPUP, true).apply()
                showSimpleDialog(activity)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing popup: ${e.message}")
            }
        }
    }

    private fun showSimpleDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Informasi")
            .setMessage("Selamat menikmati konten secara gratis. Anda dapat mendukung pengembangan ekstensi ini melalui halaman GitHub kami.")
            .setPositiveButton("Mulai Menonton") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Buka GitHub") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal membuka browser: ${e.message}")
                }
            }
            .setCancelable(true)
            .show()
    }
}
