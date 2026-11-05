package com.llamaaistudio.app.util

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The phone's DNS first; if that can't resolve a host (Private DNS / DNS-based ad-blockers
 * sometimes block api.github.com, or the carrier's DNS hiccups), ask Cloudflare and then Google
 * over HTTPS — the same way Brave and Chrome's secure DNS do. The DoH servers are reached by IP,
 * so this works even when normal DNS is completely broken.
 */
class FallbackDns(bootstrap: OkHttpClient) : Dns {
    private val doh: List<Dns> = listOf(
        "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1"),
        "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4"),
    ).map { (url, ips) ->
        DnsOverHttps.Builder().client(bootstrap).url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val first = try {
            return Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) { e }
        for (d in doh) {
            try {
                val r = d.lookup(hostname)
                if (r.isNotEmpty()) return r
            } catch (_: Exception) {
            }
        }
        throw first
    }
}
