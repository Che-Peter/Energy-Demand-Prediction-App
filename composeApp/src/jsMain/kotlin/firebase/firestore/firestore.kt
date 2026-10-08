@file:JsModule("firebase/firestore")
@file:JsNonModule
package firebase.firestore

import firebase.App
import kotlin.js.Promise

// Core
external fun getFirestore(app: App = definedExternally): Firestore

// Collections & documents
external fun collection(firestore: Firestore, path: String): CollectionReference
external fun collection(documentRef: DocumentReference, path: String): CollectionReference

external fun doc(collection: CollectionReference, path: String): DocumentReference
external fun doc(firestore: Firestore, path: String): DocumentReference

// Reads
external fun getDoc(documentRef: DocumentReference): Promise<DocumentSnapshot>
external fun getDocs(query: Query): Promise<QuerySnapshot>

// Writes
external interface SetOptions { var merge: Boolean? }

external fun setDoc(
    documentRef: DocumentReference,
    data: Any?,
    options: SetOptions = definedExternally
): Promise<Unit>

external fun deleteDoc(documentRef: DocumentReference): Promise<Unit>

// Queries (modular v9 style)
external fun where(fieldPath: String, opStr: String, value: Any?): QueryConstraint
external fun query(query: Query, vararg constraints: QueryConstraint): Query

// Batch writes (modular v9 style)
external fun writeBatch(firestore: Firestore): WriteBatch

// Interfaces
external interface Firestore
external interface CollectionReference : Query { val path: String }
external interface DocumentReference { val id: String }
external interface Query
external interface QueryConstraint

external interface DocumentSnapshot {
    val exists: Boolean
    fun data(): dynamic
}

external interface QuerySnapshot {
    val docs: Array<QueryDocumentSnapshot>
    val empty: Boolean
}

external interface QueryDocumentSnapshot {
    val id: String
    fun data(): dynamic
}
external interface WriteBatch {
    fun set(documentRef: DocumentReference, data: Any?): WriteBatch
    fun update(documentRef: DocumentReference, data: Any?): WriteBatch
    fun delete(documentRef: DocumentReference): WriteBatch
    fun commit(): Promise<Unit>
}