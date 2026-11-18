package com.minim.music.perf

import android.content.ContentValues
import com.minim.fermata.android.SqliteEventStore
import com.minim.fermata.log.Event
import com.minim.fermata.log.EventBody
import com.minim.fermata.log.EventContext
import com.minim.fermata.log.EventId
import com.minim.fermata.log.Source
import com.minim.fermata.log.TrackRef
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.stats.Days
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * 6.9.0 b78: a realistic big library for the timing tests: a year of listening (about 11k counted
 * plays, their hourly listening rows), daily tapes, and a Fermata log of 10k+ play and skip events.
 */
object PerfFixture {
    const val DAYS = 365
    const val PLAYS_PER_DAY = 30

    class Stats(val plays: Int, val events: Int, val tapes: Int)

    fun fill(db: LibraryDatabase, today: LocalDate, seed: Int = 7): Stats {
        val r = Random(seed)
        val w = db.writableDatabase
        val zone = ZoneId.systemDefault()
        val tracks = (0 until 400).map { i -> Triple("t$i", "Song $i", "Artist ${i % 60}") }
        var plays = 0
        var tapes = 0
        val events = ArrayList<Event>()
        var counter = 1L
        w.beginTransaction()
        try {
            for ((id, title, artist) in tracks) {
                w.insert("tracks", null, ContentValues().apply {
                    put("id", id); put("title", title); put("artist", artist); put("duration_sec", 200)
                    put("thumbnail_url", "https://i.ytimg.com/vi/$id/hq.jpg"); put("source_url", "https://music.youtube.com/watch?v=$id")
                })
            }
            for (d in DAYS - 1 downTo 0) {
                val date = today.minusDays(d.toLong())
                val dayKey = Days.key(date)
                val n = PLAYS_PER_DAY - 5 + r.nextInt(11)
                for (k in 0 until n) {
                    val tr = tracks[(r.nextDouble() * r.nextDouble() * tracks.size).toInt()]
                    val hour = 7 + r.nextInt(16)
                    val at = date.atTime(hour, r.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
                    w.insert("plays", null, ContentValues().apply {
                        put("track_id", tr.first); put("played_at", at); put("listened_ms", 180_000L); put("tracked", 1)
                    })
                    w.execSQL(
                        "INSERT INTO listen_time(day, hour, track_id, title, artist, thumbnail_url, ms) VALUES(?,?,?,?,?,?,?) " +
                            "ON CONFLICT(day, hour, track_id, device) DO UPDATE SET ms = ms + excluded.ms",
                        arrayOf(dayKey, hour, tr.first, tr.second, tr.third, "https://i.ytimg.com/vi/${tr.first}/hq.jpg", 180_000L)
                    )
                    plays++
                    val ref = TrackRef(tr.first, tr.second, tr.third, 200_000L, "https://i.ytimg.com/vi/${tr.first}/hq.jpg")
                    val ctx = EventContext(at, hour, date.dayOfWeek.value, source = Source.RADIO)
                    events += Event(EventId("perfphone", counter++), ctx, EventBody.Play(ref, 180_000L, at))
                    if (k % 4 == 0) {
                        val sk = EventContext(at + 1000, hour, date.dayOfWeek.value, source = Source.RADIO)
                        events += Event(EventId("perfphone", counter++), sk, EventBody.Skip(ref, 8_000L))
                    }
                }
                for (k in 0 until 3) {
                    val vid = "v${r.nextInt(120)}"
                    val hour = 18 + r.nextInt(5)
                    val at = date.atTime(hour, r.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
                    w.insert("tape_plays", null, ContentValues().apply {
                        put("video_id", vid); put("title", "Tape $vid"); put("channel", "Channel ${vid.hashCode() and 15}")
                        put("channel_id", "UC${vid.hashCode() and 15}"); put("duration_sec", 1200); put("played_at", at); put("day", dayKey)
                    })
                    w.execSQL(
                        "INSERT OR REPLACE INTO tape_listen_time(day, hour, video_id, speed, title, channel, channel_id, duration_sec, ms) VALUES(?,?,?,?,?,?,?,?,?)",
                        arrayOf(dayKey, hour, vid, 100, "Tape $vid", "Channel ${vid.hashCode() and 15}", "UC${vid.hashCode() and 15}", 1200, 900_000L)
                    )
                    tapes++
                }
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        SqliteEventStore.inDatabase(LibraryDatabase.FERMATA_LOG_TABLE) { db.writableDatabase }.appendAll(events)
        return Stats(plays, events.size, tapes)
    }
}
