@file:JsModule("firebase/auth")
@file:JsNonModule
package firebase.auth
import firebase.App
import kotlin.js.Promise


external fun setPersistence(auth: Auth, persistence: dynamic): Promise<Unit>
external val browserLocalPersistence: dynamic
external fun onAuthStateChanged(auth: Auth, nextOrObserver: (User?) -> Unit)

external fun getAuth(app: App = definedExternally): Auth

external fun createUserWithEmailAndPassword(
    auth: Auth,
    email: String,
    password: String
): Promise<UserCredential>

external fun signInWithEmailAndPassword(
    auth: Auth,
    email: String,
    password: String
): Promise<UserCredential>

external fun signOut(auth: Auth): Promise<Unit>

external interface Auth {
    val currentUser: User?
}

external interface UserCredential {
    val user: User?
}

external interface User {
    val uid: String
    val email: String?
    val emailVerified: Boolean

    fun sendEmailVerification(): Promise<Unit>
    fun reload(): Promise<Unit>
}
external fun sendPasswordResetEmail(auth: Auth, email: String): Promise<Unit>

