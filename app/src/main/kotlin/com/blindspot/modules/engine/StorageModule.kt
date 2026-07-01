package com.blindspot.modules.engine

import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.persistence.Persistence
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-host segmented discovery store.
 *
 *   hosts:   host -> (normalized path -> visited boolean)
 *   sources: host -> (normalized path -> set of JS source paths it was found in)
 *
 * `visited` is true once the path is observed in live proxy traffic. `sources`
 * records every JavaScript file an endpoint was extracted from (provenance for
 * the "Show Path" view); paths only ever seen in proxy traffic have none.
 * Deduplication is by (host, path): an endpoint is a single row regardless of
 * how many bundles reference it, with all those bundles aggregated as sources.
 *
 * State is checkpointed into Burp's project-scoped persistence so accumulated
 * discoveries (especially deprecated/forgotten endpoints) survive an extension
 * reload or a Burp crash. Writes are batched on a background thread to avoid
 * I/O thrash on high-volume passive capture.
 */
object StorageModule {

    private const val ROOT_KEY = "blindspot.hostMatrix"
    private const val FLUSH_SECONDS = 5L
    private const val SOURCE_DELIMITER = "\n"

    /** Outcome of a discovery registration, so the UI can add vs. update a row. */
    data class DiscoveryResult(val isNew: Boolean, val sourceAdded: Boolean)

    private val hosts = ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>()
    private val sources = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableSet<String>>>()

    private var persistence: Persistence? = null
    private val dirty = AtomicBoolean(false)
    private val flusher: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "blindspot-persistence").apply { isDaemon = true }
        }

    /**
     * Loads any previously persisted state and starts the periodic flush.
     * Safe to skip entirely (e.g. unit tests) — the store works in-memory.
     */
    fun init(persistence: Persistence) {
        this.persistence = persistence
        try {
            val root = persistence.extensionData().getChildObject(ROOT_KEY)
            if (root != null) {
                for (host in root.childObjectKeys()) {
                    val hostObj = root.getChildObject(host) ?: continue

                    val map = ConcurrentHashMap<String, Boolean>()
                    for (path in hostObj.booleanKeys()) {
                        map[path] = hostObj.getBoolean(path) ?: false
                    }
                    if (map.isNotEmpty()) hosts[host] = map

                    val srcMap = ConcurrentHashMap<String, MutableSet<String>>()
                    for (path in hostObj.stringKeys()) {
                        val stored = hostObj.getString(path) ?: continue
                        val set = ConcurrentHashMap.newKeySet<String>()
                        set.addAll(stored.split(SOURCE_DELIMITER).filter { it.isNotBlank() })
                        if (set.isNotEmpty()) srcMap[path] = set
                    }
                    if (srcMap.isNotEmpty()) sources[host] = srcMap
                }
            }
        } catch (_: Exception) {
            // Corrupt/incompatible persisted state — start fresh rather than crash.
        }
        flusher.scheduleWithFixedDelay(
            { flushIfDirty() }, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS
        )
    }

    /**
     * Registers a discovery route. If brand new it is stored as unvisited (false).
     * @param source a JS file path the endpoint was found in, if any. Added to the
     *               endpoint's source set even when the path is already known.
     * @return whether the path is a first-time discovery and whether this call
     *         contributed a source not seen before for that path.
     */
    fun registerDiscovery(host: String, cleanPath: String, source: String? = null): DiscoveryResult {
        if (host.isBlank() || cleanPath.isBlank()) return DiscoveryResult(false, false)
        val map = hosts.computeIfAbsent(host) { ConcurrentHashMap() }
        val isNew = map.putIfAbsent(cleanPath, false) == null

        var sourceAdded = false
        if (!source.isNullOrBlank()) {
            val src = sources.computeIfAbsent(host) { ConcurrentHashMap() }
            val set = src.computeIfAbsent(cleanPath) { ConcurrentHashMap.newKeySet() }
            sourceAdded = set.add(source)
        }
        if (isNew || sourceAdded) dirty.set(true)
        return DiscoveryResult(isNew, sourceAdded)
    }

    /**
     * Marks a path as actively visited/requested through the proxy.
     * @return true if the path was previously unvisited (allowing a red→green flip).
     */
    fun markAsVisited(host: String, cleanPath: String): Boolean {
        if (host.isBlank() || cleanPath.isBlank()) return false
        val map = hosts.computeIfAbsent(host) { ConcurrentHashMap() }
        val previousState = map.put(cleanPath, true)
        val changed = previousState == null || previousState == false
        if (changed) dirty.set(true)
        return changed
    }

    fun isVisited(host: String, cleanPath: String): Boolean {
        return hosts[host]?.get(cleanPath) == true
    }

    /** All JS files an endpoint was found in (sorted), or empty if proxy-only. */
    fun getSources(host: String, cleanPath: String): List<String> {
        return sources[host]?.get(cleanPath)?.sorted() ?: emptyList()
    }

    fun getHosts(): Set<String> = hosts.keys.toSortedSet()

    fun getPathsForHost(host: String): Map<String, Boolean> {
        return hosts[host]?.toMap() ?: emptyMap()
    }

    /** Clears a single host's maps, or everything when [host] is null. */
    fun clear(host: String? = null) {
        if (host == null) {
            hosts.clear()
            sources.clear()
        } else {
            hosts.remove(host)
            sources.remove(host)
        }
        dirty.set(true)
    }

    /** Flushes any pending changes and stops the background flusher. */
    fun shutdown() {
        flushIfDirty()
        flusher.shutdown()
    }

    private fun flushIfDirty() {
        val p = persistence ?: return
        // Only flush when something changed; reset the flag up front and restore
        // it on failure so a transient error retries on the next cycle.
        if (!dirty.compareAndSet(true, false)) return
        try {
            val root = PersistedObject.persistedObject()
            for ((host, paths) in hosts) {
                val hostObj = PersistedObject.persistedObject()
                for ((path, visited) in paths) hostObj.setBoolean(path, visited)
                sources[host]?.forEach { (path, set) ->
                    hostObj.setString(path, set.joinToString(SOURCE_DELIMITER))
                }
                root.setChildObject(host, hostObj)
            }
            p.extensionData().setChildObject(ROOT_KEY, root)
        } catch (_: Exception) {
            dirty.set(true)
        }
    }
}
