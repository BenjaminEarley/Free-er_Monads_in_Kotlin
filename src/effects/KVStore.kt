package effects

import Effect
import Program
import flatMap
import interpose
import interpret
import perform

fun <V, A> Program<A>.runKVStore(data: MutableMap<String, V>): Program<A> =
    interpret<KVStore<*>, A> { op, resume ->
        when (op) {
            is Get<*> -> {
                val valFromDb = data[op.key] ?: op.default
                resume(valFromDb)
            }

            is Put<*> -> {
                data[op.key] = op.value as V
                resume(Unit)
            }
        }
    }

fun <A> Program<A>.runKVStoreAsync(data: MutableMap<String, Any?>): Program<A> =
    interpret<KVStore<*>, A> { op, resume ->
        when (op) {
            is Get<*> -> {
                performIO {
                    println("  [IO] Reading key: ${op.key}")
                    data[op.key] ?: op.default
                }.flatMap { resume(it) }
            }

            is Put<*> -> {
                performIO {
                    println("  [IO] Writing key: ${op.key} = ${op.value}")
                    data[op.key] = op.value
                }.flatMap { resume(Unit) }
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
    interpose<KVStore<*>, A> { op, resume ->
        when (op) {
            is Get<*> -> {
                perform(Log("AUDIT", "GET ${op.key}"))
                    .flatMap { perform(op) }
                    .flatMap { result -> resume(result) }
            }

            is Put<*> -> {
                perform(Log("AUDIT", "PUT ${op.key} = ${op.value}"))
                    .flatMap { perform(op) }
                    .flatMap { resume(Unit) }
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
