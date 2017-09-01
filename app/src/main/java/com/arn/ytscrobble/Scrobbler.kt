package com.arn.ytscrobble

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Handler
import android.preference.PreferenceManager
import android.webkit.WebSettings
import de.umass.lastfm.*
import de.umass.lastfm.scrobble.ScrobbleResult
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL


/**
 * Created by arn on 18-03-2017.
 */

internal class Scrobbler constructor(private val c: Context, private val handler: Handler? = null) : AsyncTask<String, Any, Any?>() {
    lateinit private var prefs: SharedPreferences
//    private val handler: Handler? = null
    lateinit private var command: String
    private var subCommand: String? = null


    override fun doInBackground(vararg s: String): Any? {
        prefs = PreferenceManager.getDefaultSharedPreferences(c)
        command = s[0]
        if (!isNetworkAvailable) {
            publishProgress("You are offline")
            return null
        }
        try {
            var reAuthNeeded = false
            var session: Session? = null
            Caller.getInstance().userAgent = WebSettings.getDefaultUserAgent(c)
//            Caller.getInstance().isDebugMode = true

            val key: String = prefs.getString("sesskey", "")
            var username: String? = prefs.getString("username", null)

            if (key.length < 5 && token.length > 5) {
                session = Authenticator.getSession(token, Stuff.LAST_KEY, Stuff.LAST_SECRET)
                if (session != null) {
                    username = session.username
                    prefs.edit()
                            .putString("username", username)
                            .apply()
                }
            } else if (key.length > 5)
                session = Session.createSession(Stuff.LAST_KEY, Stuff.LAST_SECRET, key)
            else
                reAuthNeeded = true

            if (session == null || username == null)
                reAuthNeeded = true

            if (!reAuthNeeded) {
                //publishProgress("sess_key: " + session.getKey());
                prefs.edit().putString("sesskey", session!!.key).apply()

                when (s[0]) {
                    Stuff.CHECKAUTH -> return null
                    Stuff.GET_RECENTS -> {
                        subCommand = s[1]
                        publishProgress(User.getRecentTracks(username, Integer.parseInt(subCommand), 15, Stuff.LAST_KEY))
                        return User.getLovedTracks(username, Stuff.LAST_KEY)
                    }
                    Stuff.GET_LOVED -> return User.getLovedTracks(username, Stuff.LAST_KEY)
                    Stuff.LOVE -> return Track.love(s[1], s[2], session)
                    Stuff.UNLOVE -> return Track.unlove(s[1], s[2], session)
                    Stuff.TRACK_HERO -> {
                        //s[1] = page url, s[2] = api large image url
                        val url = URL(s[1])
                        val urlConnection = url.openConnection() as HttpURLConnection
                        val scrapped = mutableListOf<String?>()
                        try {
                            val resp = slurp(urlConnection.inputStream, 1024)

                            //0
                            var idx = resp.indexOf("cover-art")
                            var img = s[2]
                            var idx2: Int
                            if (idx > -1) {
                                idx = resp.lastIndexOf("src=", idx) + 5
                                idx2 = resp.indexOf("\"", idx)
                                img = resp.substring(idx, idx2)
                                if (img.contains("4128a6eb29f94943c9d206c08e625904"))
                                    img = s[2]
                            }
                            scrapped.add(img)
                            //1
                            idx = resp.indexOf("charts/sparkline")
                            if (idx > -1) {
                                idx = resp.indexOf("[", idx) + 1
                                idx2 = resp.indexOf("]", idx)
                                val chart = resp.substring(idx, idx2)
                                scrapped.add(chart)
                            } else
                                scrapped.add(null)
                        } finally {
                            urlConnection.disconnect()
                        }
                        return scrapped
                    }
                }

                //for scrobble or love data: s[0] = tag, s[1] = artist, s[2] = song

                var result: ScrobbleResult? = null
                val now = (System.currentTimeMillis() / 1000).toInt()
                if (s[0] == Stuff.NOW_PLAYING) {
                    val hash = s[1].hashCode() + s[2].hashCode()
                    val correction = Track.getCorrection(s[1], s[2], Stuff.LAST_KEY)
                    if (correction != null && (correction.artist != null && correction.artist.trim { it <= ' ' } != "" || correction.name != null && correction.name.trim { it <= ' ' } != "")) {
                        Stuff.log(c, "valid track: " + correction.toString())
                        //                    if (correction.getArtist() != null)
                        //                        s[1] = correction.getArtist();
                        //                    if (correction.getName() != null)
                        //                        s[2] = correction.getName();
                        result = Track.updateNowPlaying(s[1], s[2], session)
                    } else {
                        publishProgress("not a song")
                        (handler as NLService.ScrobbleHandler).remove(hash)
                        handler.notification(c.getString(R.string.invalid_artist),s[1]+" "+ s[2], c.getString(R.string.not_scrobling), android.R.drawable.stat_notify_error)

                    }

                } else if (s[0] == Stuff.SCROBBLE)
                    result = Track.scrobble(s[1], s[2], now, session)
                try {
                    if (result != null && !(result.isSuccessful && !result.isIgnored)) {
                        val hash = s[1].hashCode() + s[2].hashCode()
                        //                        scrobbledHashes.add(hash);
                        (handler as NLService.ScrobbleHandler)
                                .notification(c.getString(R.string.network_error),s[1]+" "+ s[2], c.getString(R.string.not_scrobling), android.R.drawable.stat_notify_error)
                    }
                } catch (e: NullPointerException) {
                    publishProgress(s[0] + ": NullPointerException")
                }

            } else
                reAuth()
        } catch (e: Exception) {
            publishProgress(e.message)
        }

        // adb shell am start -W -a android.intent.action.VIEW -d "http://maare.ga:10003/auth" com.arn.ytscrobble
        return null
    }

    //header-expanded-image
    private fun reAuth() {
        publishProgress("Authorize LastFM")
        PreferenceManager.getDefaultSharedPreferences(c)
                .edit()
                .remove("sesskey")
                .apply()
        token = Authenticator.getToken(Stuff.LAST_KEY)
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.last.fm/api/auth?api_key=" +
                Stuff.LAST_KEY + "&token=" + token))
        c.startActivity(browserIntent)
    }

    private val isNetworkAvailable: Boolean
        get() {
            val connectivityManager = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }

    override fun onProgressUpdate(vararg values: Any) {
        super.onProgressUpdate(*values)
        val res = values[0]
        if (res is PaginatedResult<*>) {
            handler?.obtainMessage(0, subCommand!!.toInt(), 0, Pair(command,res))?.sendToTarget()
        } else if (res is String)
            Stuff.toast(c, values[0].toString())
    }

    override fun onPostExecute(res: Any?) {
        //do stuff
        if (res is PaginatedResult<*>) {
//            val b = Bundle()
//            b.putString("command", command)
            if (command == Stuff.GET_RECENTS)
                command = Stuff.GET_LOVED
            handler?.obtainMessage(0, Pair(command,res))?.sendToTarget()
        } else if (res is Result) {
            if (res.isSuccessful)
                Stuff.toast(c, command)
            else
                Stuff.toast(c, command + " failed!")
        } else if (command == Stuff.TRACK_HERO && res is MutableList<*>) {
//            val s = res as MutableList<String?>
            handler?.obtainMessage(0, Pair(command, res))?.sendToTarget()
            //make graph
        }
    }

    companion object {
        private var token = ""

        private fun slurp(`is`: InputStream, bufferSize: Int): String {
            val buffer = CharArray(bufferSize)
            val out = StringBuilder()
            try {
                InputStreamReader(`is`, "UTF-8").use { `in` ->
                    while (true) {
                        val rsz = `in`.read(buffer, 0, buffer.size)
                        if (rsz < 0)
                            break
                        out.append(buffer, 0, rsz)
                    }
                }
            } catch (ex: IOException) {
                ex.printStackTrace()
                return ""
            }

            return out.toString()
        }
    }
}//    private static ArrayList<Integer> scrobbledHashes= new ArrayList<>();
