@file:Suppress("UNCHECKED_CAST")

package effects

import Effect
import Program
import handle
import intercept
import perform

fun <V, A> Program<A>.kvStore(data: MutableMap<String, V>): Program<A> =
    handle<KVStore<*>, A> { op ->
        when (op) {
            is Get<*> -> {
                data[op.key] ?: op.default
            }

            is Put<*> -> {
                data[op.key] = op.value as V
            }
        }
    }

fun <V, A> Program<A>.runKVStoreAsync(data: MutableMap<String, V>): Program<A> =
    handle<KVStore<*>, A> { op ->
        when (op) {
            is Get<*> -> {
                performIO {
                    println("  [IO] Reading key: ${op.key}")
                    data[op.key] ?: op.default
                }.bind()
            }

            is Put<*> -> {
                performIO {
                    println("  [IO] Writing key: ${op.key} = ${op.value}")
                    data[op.key] = op.value as V
                }.bind()
            }
        }
    }

sealed interface KVStore<out R> : Effect<R>

data class Get<T>(
    val key: String,
    val default: T,
) : KVStore<T>

data class Put<T>(
    val key: String,
    val value: T,
) : KVStore<Unit>

fun <A> Program<A>.auditKVStore(): Program<A> =
    intercept<KVStore<*>, A> { op ->
        when (op) {
            is Get<*> -> {
                perform(Log("AUDIT", "GET ${op.key}")).bind()
                perform(op).bind()
            }

            is Put<*> -> {
                perform(Log("AUDIT", "PUT ${op.key} = ${op.value}")).bind()
                perform(op).bind()
            }
        }
    }

fun <T> get(
    key: String,
    default: T,
) = perform(Get(key, default))

fun <T> put(
    key: String,
    value: T,
) = perform(Put(key, value))
