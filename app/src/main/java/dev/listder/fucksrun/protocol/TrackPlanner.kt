package dev.listder.fucksrun.protocol

import kotlin.math.cos
import kotlin.math.hypot

object TrackPlanner {
    fun distance(a: LatLon, b: LatLon): Double {
        val lonMeters = 111320 * cos(Math.toRadians((a.latitude + b.latitude) / 2))
        return hypot((a.longitude - b.longitude) * lonMeters, (a.latitude - b.latitude) * 111320)
    }

}
