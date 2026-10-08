@file:JsModule("firebase/database")
@file:JsNonModule
package firebase.database

import firebase.App
import kotlin.js.Promise

// Modular API entry points
external fun getDatabase(app: App = definedExternally): Database
external fun ref(database: Database, path: String): DatabaseReference

// Basic operations
external fun get(ref: DatabaseReference): Promise<DataSnapshot>
external fun set(ref: DatabaseReference, value: Any?): Promise<Unit>
external fun update(ref: DatabaseReference, value: Any?): Promise<Unit>
external fun remove(ref: DatabaseReference): Promise<Unit>
external fun get(query: Query): Promise<DataSnapshot>

// Query builders
external fun query(ref: DatabaseReference, vararg constraints: QueryConstraint): Query
external fun orderByChild(path: String): QueryConstraint
external fun endAt(query: Query, value: Double): Query

// Listener externals
external fun onValue(query: Query, callback: (DataSnapshot) -> Unit)
external fun off(query: Query)

// Interfaces
external interface Database

external interface DataSnapshot {
    fun exists(): Boolean
    fun `val`(): Any?
    fun child(path: String): DataSnapshot

    fun forEach(action: (DataSnapshot) -> Boolean?): Boolean

    val ref: DatabaseReference?
}

external interface DatabaseReference : Query {
    val key: String?
    fun child(path: String): DatabaseReference
}

external interface Query

external interface QueryConstraint
