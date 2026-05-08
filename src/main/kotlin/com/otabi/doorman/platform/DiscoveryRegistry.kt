package com.otabi.doorman.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

data class Discovery(val address: String, val rssi: Int, val name: String?, val advHex: String, val tsMs: Long = System.currentTimeMillis())

class DiscoveryRegistry {
  private val map = ConcurrentHashMap<String, Discovery>()
  private val _flow = MutableSharedFlow<Discovery>(replay = 1)
  val discoveryFlow = _flow.asSharedFlow()

  fun upsert(d: Discovery) {
    val entry = d.copy(tsMs = System.currentTimeMillis())
    map[entry.address] = entry
    _flow.tryEmit(entry)
  }

  fun get(address: String): Discovery? = map[address]
  fun findByNameSubstring(sub: String): Discovery? = map.values.firstOrNull { it.name?.contains(sub, ignoreCase = true) == true }
  fun bestByRssi(nameSubstring: String? = null): Discovery? {
    val candidates = if (nameSubstring.isNullOrBlank()) map.values else map.values.filter { it.name?.contains(nameSubstring, ignoreCase = true) == true }
    return candidates.maxByOrNull { it.rssi }
  }
  fun all(): List<Discovery> = map.values.toList()
  fun pruneOlderThan(ageMs: Long) { val cutoff = System.currentTimeMillis() - ageMs; map.entries.removeIf { it.value.tsMs < cutoff } }
}
