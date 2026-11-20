package com.swordfish.lemuroid.app.shared.covers

import okhttp3.Interceptor
import okhttp3.Response

/**
 * The libretro thumbnails mirror on GitHub keeps some covers as links to another file (the
 * same picture under another name). Raw GitHub serves such a link as a short text naming the
 * file it points to: fetch that file instead, from the same folder.
 */
object FollowThumbnailLinksInterceptor : Interceptor {
    private const val HOST = "raw.githubusercontent.com"
    private const val MAX_LINK_LENGTH = 512L
    private const val MAX_HOPS = 3

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        repeat(MAX_HOPS) {
            if (request.url.host != HOST || !response.isSuccessful) return response
            val type = response.body?.contentType()
            if (type == null || type.type != "text") return response
            val target = response.peekBody(MAX_LINK_LENGTH).string().trim()
            if (target.isEmpty() || target.contains('/') || target.contains('\n') || !target.endsWith(".png")) return response
            response.close()
            val url = request.url.newBuilder().setPathSegment(request.url.pathSize - 1, target).build()
            request = request.newBuilder().url(url).build()
            response = chain.proceed(request)
        }
        return response
    }
}
