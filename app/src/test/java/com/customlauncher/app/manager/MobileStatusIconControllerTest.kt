package com.customlauncher.app.manager

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileStatusIconControllerTest {
    @Test
    fun parseSlotsTreatsNullAndBlankAsEmpty() {
        assertEquals(emptySet<String>(), MobileStatusIconController.parseSlots("null\n"))
        assertEquals(emptySet<String>(), MobileStatusIconController.parseSlots("  "))
    }

    @Test
    fun parseSlotsPreservesOrderAndRemovesEmptyTokens() {
        assertEquals(
            linkedSetOf("rotate", "mobile", "alarm_clock"),
            MobileStatusIconController.parseSlots("rotate, mobile,,alarm_clock\n")
        )
    }
}
