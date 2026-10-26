package com.minim.music.data.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportNamesTest {

    @Test fun firstTryIsTheNameItself() = assertEquals("Chill Mix", ImportNames.candidate(" Chill Mix ", 1))

    @Test fun laterTriesGetANumber() = assertEquals("Chill Mix (3)", ImportNames.candidate("Chill Mix", 3))

    @Test fun blankNameGetsADefault() = assertEquals("Imported playlist", ImportNames.candidate("  ", 1))

    @Test fun lengthText() {
        assertEquals("3:05", ImportNames.length(185))
        assertEquals("", ImportNames.length(0))
    }

    @Test fun youTubeKinds() {
        assertEquals("album", ImportNames.youTubeKind("OLAK5uy_abc"))
        assertEquals("mix", ImportNames.youTubeKind("RDCLAK5uy_x"))
        assertEquals("playlist", ImportNames.youTubeKind("PL123"))
    }
}
