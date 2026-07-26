/*
 * Real-behavior replacement for the mockable android.jar's android.database.SQLException stub.
 *
 * Host tests run against AGP's mockable android.jar, whose SQLException constructor bodies are
 * stubbed out and silently DROP the message (verified: `SQLException("x").message == null`).
 * Room 3's EntityUpsertAdapter (@Upsert) relies on that message: on an INSERT conflict it
 * inspects `e.message` for "unique"/"1555"/"2067" to decide whether to fall back to UPDATE, and
 * rethrows when the message is null. Without this class, every conflicting upsert (e.g.
 * ParcelRepository.refresh -> applySnapshot -> upsertParcel) crashes with an opaque SQLException.
 *
 * Classes compiled from this source set precede the mockable jar on the test runtime classpath,
 * so this definition wins and restores standard RuntimeException message/cause behavior.
 * (Same well-known technique as providing a real android.util.Log in unit tests.)
 */
package android.database

class SQLException : RuntimeException {
    constructor() : super()
    constructor(error: String) : super(error)
    constructor(error: String, cause: Throwable?) : super(error, cause)
}
