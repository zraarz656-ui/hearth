package com.tradequest.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationTest {

    @Test
    fun `routes are unique`() {
        val routes = Destination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `routes are non blank`() {
        Destination.entries.forEach { destination ->
            assertTrue(
                "route must not be blank for $destination",
                destination.route.isNotBlank(),
            )
        }
    }

    @Test
    fun `every required screen is reachable`() {
        val expected = setOf(
            "home",
            "market_library",
            "trading",
            "challenge",
            "journal",
            "news",
            "history",
            "settings",
        )
        assertEquals(expected, Destination.entries.map { it.route }.toSet())
    }

    @Test
    fun `start destination is home`() {
        assertEquals(Destination.HOME, Destination.START)
    }

    @Test
    fun `fromRoute resolves known route`() {
        assertEquals(Destination.TRADING, Destination.fromRoute("trading"))
    }

    @Test
    fun `fromRoute returns null for unknown route`() {
        assertNull(Destination.fromRoute("does_not_exist"))
        assertNull(Destination.fromRoute(null))
    }

    @Test
    fun `primary navigation contains start destination`() {
        assertTrue(Destination.primary.contains(Destination.START))
    }

    @Test
    fun `primary navigation is a subset of all destinations`() {
        assertTrue(Destination.primary.all { it.inPrimaryNavigation })
    }

    @Test
    fun `every destination declares a label and icon`() {
        Destination.entries.forEach { destination ->
            assertNotNull(destination.icon)
            assertTrue("labelRes missing for $destination", destination.labelRes != 0)
        }
    }
}
