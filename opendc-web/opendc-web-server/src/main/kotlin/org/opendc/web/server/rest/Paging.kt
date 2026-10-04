/*
 * Copyright (c) 2026 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.web.server.rest

import io.quarkus.hibernate.orm.panache.kotlin.PanacheQuery
import kotlinx.serialization.Serializable

/** One window of a longer list, and how long the whole list is. */
@Serializable
data class Page<T>(
    val items: List<T>,
    val total: Long,
)

/** The part of a list a client asked for, held to what one response may carry. */
data class Window(
    val offset: Int,
    val limit: Int,
) {
    companion object {
        const val MAX_LIMIT = 200

        fun of(
            offset: Int,
            limit: Int,
        ): Window = Window(offset.coerceAtLeast(0), limit.coerceIn(1, MAX_LIMIT))
    }
}

fun <E : Any> PanacheQuery<E>.within(window: Window): List<E> = range(window.offset, window.offset + window.limit - 1).list()
