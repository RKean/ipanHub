package com.cartube.ytauto.queue

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * In-memory queue with persistence; single source of truth for playlist order and play head.
 */
object QueueController {

    private var repository: QueueRepository? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val items = mutableListOf<QueueItem>()
    private var currentIndex = -1
    var autoplayRelated: Boolean = false
        private set

    fun init(context: Context) {
        if (repository != null) return
        repository = QueueRepository(context.applicationContext)
        val loaded = repository!!.load()
        items.clear()
        items.addAll(loaded.items)
        currentIndex = loaded.currentIndex.coerceIn(-1, items.lastIndex)
        autoplayRelated = loaded.autoplayRelated
    }

    fun addListener(listener: () -> Unit) = listeners.addIfAbsent(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyChanged() {
        persist()
        listeners.forEach { it.invoke() }
    }

    private fun persist() {
        repository?.save(
            QueueRepository.QueueState(items.toList(), currentIndex, autoplayRelated)
        )
    }

    fun allItems(): List<QueueItem> = items.toList()
    fun currentIndex(): Int = currentIndex
    fun currentItem(): QueueItem? = items.getOrNull(currentIndex)
    fun nextItem(): QueueItem? = items.getOrNull(currentIndex + 1)
    fun hasNext(): Boolean = currentIndex + 1 < items.size
    fun hasPrevious(): Boolean = currentIndex > 0

    fun playNow(item: QueueItem) {
        val existing = items.indexOfFirst { it.videoId == item.videoId }
        if (existing >= 0) {
            currentIndex = existing
            items[existing] = item
        } else {
            items.add(0, item)
            currentIndex = 0
        }
        notifyChanged()
    }

    fun playNext(item: QueueItem) {
        val insertAt = if (currentIndex >= 0) currentIndex + 1 else items.size
        items.add(min(insertAt, items.size), item)
        notifyChanged()
    }

    fun addToEnd(item: QueueItem) {
        if (items.any { it.videoId == item.videoId }) return
        items.add(item)
        if (currentIndex < 0 && items.size == 1) currentIndex = 0
        notifyChanged()
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in 0..items.size) return
        val item = items.removeAt(from)
        items.add(to.coerceAtMost(items.size), item)
        when {
            currentIndex == from -> currentIndex = to
            from < currentIndex && to >= currentIndex -> currentIndex--
            from > currentIndex && to <= currentIndex -> currentIndex++
        }
        notifyChanged()
    }

    fun removeAt(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        when {
            items.isEmpty() -> currentIndex = -1
            index < currentIndex -> currentIndex--
            index == currentIndex -> currentIndex = min(currentIndex, items.lastIndex)
        }
        notifyChanged()
    }

    fun clear() {
        items.clear()
        currentIndex = -1
        notifyChanged()
    }

    fun jumpTo(index: Int): QueueItem? {
        if (index !in items.indices) return null
        currentIndex = index
        notifyChanged()
        return items[index]
    }

    fun advanceToNext(): QueueItem? {
        if (!hasNext()) return null
        currentIndex++
        notifyChanged()
        return currentItem()
    }

    fun backToPrevious(): QueueItem? {
        if (!hasPrevious()) return null
        currentIndex--
        notifyChanged()
        return currentItem()
    }

    fun setAutoplayRelated(enabled: Boolean) {
        autoplayRelated = enabled
        persist()
        listeners.forEach { it.invoke() }
    }
}