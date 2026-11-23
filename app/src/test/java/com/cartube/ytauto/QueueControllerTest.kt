package com.cartube.ytauto

import com.cartube.ytauto.queue.QueueController
import com.cartube.ytauto.queue.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28])
class QueueControllerTest {

    @Before
    fun setup() {
        QueueController.init(RuntimeEnvironment.getApplication())
        QueueController.clear()
    }

    private fun item(id: String) = QueueItem(id, "Title $id", "Channel", 120)

    @Test
    fun playNowInsertsAtHead() {
        QueueController.playNow(item("a"))
        assertEquals("a", QueueController.currentItem()?.videoId)
        QueueController.playNow(item("b"))
        assertEquals("b", QueueController.currentItem()?.videoId)
        assertEquals(2, QueueController.allItems().size)
    }

    @Test
    fun advanceAndBack() {
        QueueController.playNow(item("1"))
        QueueController.addToEnd(item("2"))
        QueueController.jumpTo(0)
        assertTrue(QueueController.hasNext())
        assertEquals("2", QueueController.advanceToNext()?.videoId)
        assertEquals("1", QueueController.backToPrevious()?.videoId)
    }

    @Test
    fun moveUpdatesCurrentIndex() {
        QueueController.playNow(item("1"))
        QueueController.addToEnd(item("2"))
        QueueController.addToEnd(item("3"))
        QueueController.jumpTo(0)
        QueueController.move(0, 2)
        assertEquals(2, QueueController.currentIndex())
    }

    @Test
    fun removeCurrentFallsToSameSlot() {
        QueueController.playNow(item("1"))
        QueueController.addToEnd(item("2"))
        QueueController.jumpTo(0)
        QueueController.removeAt(0)
        assertEquals("2", QueueController.currentItem()?.videoId)
        assertFalse(QueueController.hasPrevious())
    }
}