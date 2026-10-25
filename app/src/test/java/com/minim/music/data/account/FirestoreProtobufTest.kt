package com.minim.music.data.account

import com.google.firestore.v1.Value
import com.google.firestore.v1.Write
import com.google.protobuf.Timestamp
import com.google.type.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5.0: NewPipeExtractor forces protobuf-javalite 4.x, while Firestore 25 was generated for 3.x and we
 * swapped its well-known types for protolite-well-known-types 19.0.0 (the protobuf 4.x build) to avoid
 * duplicate classes. This checks that Firestore's own messages and the well-known types still build,
 * serialise and parse on that runtime.
 */
class FirestoreProtobufTest {

    @Test
    fun firestoreMessagesRoundTripOnProtobuf4() {
        val name = "projects/p/databases/(default)/documents/users/u1/likes/abc"
        val write = Write.newBuilder().setDelete(name).build()
        assertEquals(name, Write.parseFrom(write.toByteArray()).delete)

        val value = Value.newBuilder()
            .setTimestampValue(Timestamp.newBuilder().setSeconds(1_761_350_400L).setNanos(5))
            .build()
        assertEquals(1_761_350_400L, Value.parseFrom(value.toByteArray()).timestampValue.seconds)

        val geo = Value.newBuilder().setGeoPointValue(LatLng.newBuilder().setLatitude(26.1).setLongitude(91.7)).build()
        assertEquals(91.7, Value.parseFrom(geo.toByteArray()).geoPointValue.longitude, 0.0)
    }
}
