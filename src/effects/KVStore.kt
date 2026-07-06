package ffree.effects

import ffree.Effect
import ffree.Program
import ffree.handle
import ffree.intercept
import ffree.perform

inline fun <reified V, A> Program<A>.kvStore(data: MutableMap<String, V>): Program<A> =
    handle<KVStore<*>, A> { op ->
        when (op) {
            is Get<*> -> {
                if (data.containsKey(op.key)) checkGetType(op, data[op.key]) else op.default
            }

            is Put<*> -> {
                val value = op.value
                check(value is V) {
                    "KVStore handler: Put(\"${op.key}\") carries a ${typeName(value)}, which this " +
                        "store's value type cannot hold — the write would corrupt the map."
                }
                data[op.key] = value
            }
        }
    }

inline fun <reified V, A> Program<A>.kvStoreAsync(data: MutableMap<String, V>): Program<A> =
    handle<KVStore<*>, A> { op ->
        when (op) {
            is Get<*> -> {
                performIO {
                    println("  [IO] Reading key: ${op.key}")
                    if (data.containsKey(op.key)) checkGetType(op, data[op.key]) else op.default
                }.bind()
            }

            is Put<*> -> {
                val value = op.value
                check(value is V) {
                    "KVStore handler: Put(\"${op.key}\") carries a ${typeName(value)}, which this " +
                        "store's value type cannot hold — the write would corrupt the map."
                }
                performIO {
                    println("  [IO] Writing key: ${op.key} = $value")
                    data[op.key] = value
                }.bind()
            }
        }
    }

@PublishedApi
internal fun checkGetType(
    op: Get<*>,
    stored: Any?,
): Any? {
    val default = op.default
    if (stored != null &&
        default != null &&
        (isScalarLike(stored) || isScalarLike(default)) &&
        !default.javaClass.isInstance(stored) &&
        !stored.javaClass.isInstance(default)
    ) {
        throw IllegalStateException(
            "KVStore handler: Get(\"${op.key}\") expects a ${default.javaClass.simpleName} (inferred " +
                "from its default) but the store holds a ${stored.javaClass.simpleName} — returning it " +
                "would corrupt the program. Align the Get default's type with the stored value type.",
        )
    }
    return stored
}

@PublishedApi
internal fun isScalarLike(value: Any): Boolean = value is Number || value is Boolean || value is Char || value is CharSequence

@PublishedApi
internal fun typeName(value: Any?): String = if (value == null) "null" else value::class.simpleName ?: value.javaClass.name

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
    intercept<KVStore<*>, A> { op, proceed ->
        when (op) {
            is Get<*> -> {
                logAudit("GET ${op.key}").bind()
                proceed()
            }

            is Put<*> -> {
                logAudit("PUT ${op.key} = ${op.value}").bind()
                proceed()
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
