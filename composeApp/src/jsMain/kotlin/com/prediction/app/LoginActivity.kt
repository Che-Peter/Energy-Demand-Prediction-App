package com.prediction.app
import com.prediction.app.ui.theme.*
// Compose Web DOM + CSS

import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder

// Runtime state
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NoLiveLiterals
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import firebase.auth.User

// Coroutines
import kotlinx.coroutines.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Multiplatform datetime
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// JS interop (Firebase)
import firebase.auth.getAuth
import firebase.auth.createUserWithEmailAndPassword
import firebase.auth.signInWithEmailAndPassword
import firebase.database.getDatabase
import firebase.util.awaitPromise
// Custom web components
import firebase.database.get
import firebase.database.ref
import firebase.database.set
import firebase.database.update
import org.w3c.dom.HTMLInputElement
import kotlinx.browser.window
import kotlinx.browser.window
import kotlin.coroutines.resume


@Composable
fun LoginSignupScreen(
    repository: FirebaseAndIndexedDBRepository,
    onLoginSuccess: (AccountEntity) -> Unit,
    onAdminLoginSuccess: () -> Unit   // ⬅️ forward admin callback
) {
    var showSignup by remember { mutableStateOf(false) }
    if (showSignup) {
        SignupScreen(
            onSignupSuccess = { account ->
                onLoginSuccess(account)
            },
            onBack = { showSignup = false },
            repository = repository
        )
    } else {
        LoginScreen(
            repository = repository,
            onLoginSuccess = onLoginSuccess,
            onSignupClicked = { showSignup = true },
            onAdminLoginSuccess = onAdminLoginSuccess   // ⬅️ pass through
        )
    }
}


@Composable
fun LoginScreen(
    repository: FirebaseAndIndexedDBRepository,
    onLoginSuccess: (AccountEntity) -> Unit,
    onSignupClicked: () -> Unit,
    onAdminLoginSuccess: () -> Unit
) {
    val uiState = remember { mutableStateOf(LoginUiState()) }
    val coroutineScope = rememberCoroutineScope()
    var isPasswordVisible by remember { mutableStateOf(false) }
    val emailInputRef by remember { mutableStateOf<HTMLInputElement?>(null) }
    val passwordInputRef by remember { mutableStateOf<HTMLInputElement?>(null) }

    suspend fun handleUserLogin(firebaseUser: User) {
        val uid = firebaseUser.uid
        console.log("▶️ handleUserLogin: start, uid=", uid)

        var user: UserEntity? = repository.getUserFromRTDB(uid)
        console.log("ℹ️ handleUserLogin: user from RTDB =", user)

        if (user == null || user.ownedProducts.isEmpty()) {
            console.warn("⚠️ handleUserLogin: no user or empty ownedProducts")
            uiState.value = uiState.value.copy(loginStep = 0)
            return
        }

        val productIdsToTry = mutableListOf<String>()
        if (user.loggedIntoProduct.isNotEmpty() && user.ownedProducts.contains(user.loggedIntoProduct)) {
            productIdsToTry.add(user.loggedIntoProduct)
        }
        productIdsToTry.addAll(user.ownedProducts.filter { it != user!!.loggedIntoProduct })
        console.log("ℹ️ handleUserLogin: productIdsToTry =", productIdsToTry)

        var foundAccount: AccountEntity? = null

        for (productId in productIdsToTry) {
            console.log("🔍 handleUserLogin: checking productId =", productId)

            val localAccounts = repository.getAccountsFromSQLite().filter {
                it.decryptedProductId == productId && (it.primaryUuid == uid)
            }
            console.log("ℹ️ handleUserLogin: localAccounts found =", localAccounts.size)

            if (localAccounts.isNotEmpty()) {
                foundAccount = localAccounts.first()
                console.log("✅ handleUserLogin: found local account =", foundAccount)

                val updatedUser = user?.copy(
                    loggedIntoProduct = foundAccount.decryptedProductId,
                    lastLoginRole = "user",
                    lastAccessedDate = getCurrentDate(),
                    ownedProducts = listOf(foundAccount.decryptedProductId) +
                            (user.ownedProducts.filter { it != foundAccount!!.decryptedProductId } ?: emptyList()),
                    isLoggedIn = true
                )
                console.log("ℹ️ handleUserLogin: updatedUser =", updatedUser)

                if (updatedUser != null) {
                    console.log("💾 handleUserLogin: saving user to SQLite")
                    repository.saveUserToSQLite(updatedUser)
                }
                console.log("➡️ handleUserLogin: calling onLoginSuccess with local account")
                onLoginSuccess(foundAccount)
                return
            }

            val remoteAccount: AccountEntity? = repository.fetchAccountFromFirebase(productId)
            console.log("ℹ️ handleUserLogin: remoteAccount =", remoteAccount)

            if (remoteAccount != null) {
                if (remoteAccount.primaryUuid != uid) {
                    console.warn("⚠️ handleUserLogin: remote account primaryUuid mismatch, skipping")
                    if (user != null) {
                        user = user.copy(ownedProducts = user.ownedProducts.filter { it != productId })
                    }
                    continue
                }
                foundAccount = remoteAccount
                console.log("✅ handleUserLogin: found remote account =", foundAccount)

                val updatedUser = user?.copy(
                    loggedIntoProduct = remoteAccount.decryptedProductId,
                    lastLoginRole = "user",
                    lastAccessedDate = getCurrentDate(),
                    ownedProducts = listOf(remoteAccount.decryptedProductId) +
                            (user.ownedProducts?.filter { it != remoteAccount.decryptedProductId } ?: emptyList()),
                    isLoggedIn = true
                )
                console.log("ℹ️ handleUserLogin: updatedUser =", updatedUser)

                if (updatedUser != null) {
                    console.log("💾 handleUserLogin: saving user to SQLite")
                    repository.saveUserToSQLite(updatedUser)
                }

                val syncedAccount = remoteAccount.copy(
                    lastUpdated = 1L,
                    lastDate = remoteAccount.creationDate,
                    sn = 1L
                )
                console.log("💾 handleUserLogin: saving account to SQLite =", syncedAccount)
                repository.saveAccountToSQLite(syncedAccount)

                console.log("➡️ handleUserLogin: calling onLoginSuccess with synced account")
                onLoginSuccess(syncedAccount)
                return
            }
        }

        console.warn("⚠️ handleUserLogin: no account found, resetting loginStep")
        uiState.value = uiState.value.copy(loginStep = 0)
    }
    @NoLiveLiterals
    fun tryLogin() {
        coroutineScope.launch {
            val email = uiState.value.name.trim()
            val password = uiState.value.password
            console.log("▶️ tryLogin: start, email=", email)

            // Step 1: basic validation
            if (email.isBlank() || password.isBlank()) {
                uiState.value = uiState.value.copy(errorMessage = I18n.t("error_enter_all_fields"))
                if (email.isBlank()) emailInputRef?.focus() else passwordInputRef?.focus()
                console.error("❌ tryLogin: email or password blank")
                return@launch
            }

            uiState.value = uiState.value.copy(loginStep = 1)
            try {
                console.log("ℹ️ tryLogin: deleting all users from SQLite")
                repository.deleteAllUsersFromSQLite()

                console.log("ℹ️ tryLogin: signing in user with Firebase")
                val result = repository.signInUser(email, password)
                console.log("ℹ️ tryLogin: signInUser result =", result)

                val firebaseUser = result.user ?: run {
                    console.error("❌ tryLogin: invalid login, no Firebase user")
                    uiState.value = uiState.value.copy(
                        loginStep = 0,
                        errorMessage = I18n.t("invalid_login")
                    )
                    return@launch
                }

                val uid = firebaseUser.uid
                console.log("✅ tryLogin: Firebase user signed in, uid=", uid)

                uiState.value = uiState.value.copy(loginStep = 2)
                console.log("➡️ tryLogin: calling handleUserLogin")
                handleUserLogin(firebaseUser)    // suspend call
            } catch (e: Exception) {
                console.error("❌ tryLogin: exception during login", e)
                uiState.value = uiState.value.copy(loginStep = 0)
            }
        }
    }


    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            height(100.vh)
            backgroundColor(Color(lightAppBackground))
            fontFamily("sans-serif")
        }
    }) {
        Div({
            style {
                width(100.percent)
                height(40.px)
                color(Color(TextColor))
                backgroundColor(Color(lightAppHeader))
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
            }
        }) {
            Text("LOGIN")
        }

        // Center form
        Div({
            style {
                flex(1)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                alignItems(AlignItems.Center)
                justifyContent(JustifyContent.Center)
                gap(20.px)
                padding(24.px)
            }
        }) {
            // Email field
            Div({
                style {
                    width(90.percent)
                    color(Color(TextColor))
                    maxWidth(400.px)
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                }
            }) {
                Text("Email")
                Div({ style { height(4.px) } })
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("border-bottom", "1px solid #ccc")
                        paddingBottom(4.px)
                    }
                }) {
                    IconForPanel("email", sizePx = 24, tint = "#FFFFFF")
                    Input(type = InputType.Text) {
                        value(uiState.value.name)
                        onInput { ev -> uiState.value = uiState.value.copy(name = ev.value, errorMessage = null) }
                        placeholder("Enter your email")
                        style {
                            flex(1)
                            marginLeft(8.px)
                            border(0.px)
                            outline("none")
                            backgroundColor(Color.transparent)
                            fontSize(16.px)
                            color(Color("#BFBFBF"))
                        }
                    }
                }
            }

            Div({ style { height(0.px) } })

            // Password field with visibility toggle
            Div({
                style {
                    width(90.percent)
                    maxWidth(400.px)
                    color(Color(TextColor))
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                }
            }) {
                Text("Password")
                Div({ style { height(4.px) } })
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("border-bottom", "1px solid #ccc")
                        paddingBottom(4.px)
                    }
                }) {
                    // Left icon
                    IconForPanel("password", sizePx = 24, tint = "#FFFFFF")

                    // Input field
                    Input(type = if (isPasswordVisible) InputType.Text else InputType.Password) {
                        value(uiState.value.password)
                        onInput { ev -> uiState.value = uiState.value.copy(password = ev.value, errorMessage = null) }
                        placeholder("Enter your password")
                        style {
                            flex(1)
                            marginLeft(8.px)
                            border(0.px)
                            outline("none")
                            backgroundColor(Color.transparent)
                            fontSize(16.px)
                            color(Color("#BFBFBF"))
                        }
                    }

                    // Right toggle icon
                    Button(attrs = {
                        style {
                            backgroundColor(Color.transparent)
                            border(0.px)
                            cursor("pointer")
                            marginLeft(8.px)
                        }
                        onClick { isPasswordVisible = !isPasswordVisible }
                    }) {
                        IconForPanel(if (isPasswordVisible) "visible" else "invisible", sizePx = 24, tint = "#FFFFFF")
                    }
                }
            }

            // Error message prompt (space always reserved)
            Div({
                style {
                    height(20.px)
                    marginTop(4.px)
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.Center)
                    alignItems(AlignItems.Center)
                    color(Color(ErrorColor))   // apply your red hex string here
                    fontSize(14.px)
                    textAlign("center")
                }
            }) {
                uiState.value.errorMessage?.let {
                    Text(it)
                }
            }

            // Forgot password link
            Div({
                style {
                    width(90.percent)
                    maxWidth(400.px)
                    color(Color(TextColor))
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.FlexEnd)
                    marginTop(2.px)
                }
            }) {
                Button(attrs = {
                    style {
                        backgroundColor(Color.transparent)
                        border(0.px)
                        color(Color(TextColor))
                        cursor("pointer")
                        fontSize(14.px)
                    }
                    onClick {
                        if (uiState.value.name.isNotEmpty()) {
                            coroutineScope.launch { repository.sendPasswordResetEmail(uiState.value.name) }
                        } else {
                            uiState.value = uiState.value.copy(errorMessage = I18n.t("enter_email_for_password_reset"))
                        }
                    }
                }) {
                    Text(I18n.t("forgot_password"))
                }
            }

            Button(attrs = {
                style {
                    marginTop(8.px)
                    padding(8.px, 16.px)
                    width(200.px)              // fixed width for ~25 characters
                    backgroundColor(Color(theIconColor))
                    color(Color(TextColor))
                    borderRadius(6.px)
                    fontSize(14.px)
                    cursor("pointer")
                }
                onClick { tryLogin() }
            }) {
                Text(
                    when (uiState.value.loginStep) {
                        1 -> I18n.t("authenticating")
                        2, 3 -> I18n.t("finalizing_setup")
                        4 -> I18n.t("login_success")
                        else -> I18n.t("sign_in")
                    }
                )
            }
            // Do not have account link
            Div({
                style { marginTop(12.px) }
            }) {
                Button(attrs = {
                    style {
                        backgroundColor(Color.transparent)
                        border(0.px)
                        color(Color(lightAppHeader))
                        cursor("pointer")
                        fontSize(14.px)
                        color(Color(TextColor))
                    }
                    onClick {
                        onSignupClicked()
                    }
                }) {
                    Text(I18n.t("do_not_have_account"))
                }
            }
        }
    }

}


fun getCurrentDate(): String {
    return Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date.toString() // ISO-8601 format (YYYY-MM-DD)
}

data class LoginUiState(
    val name: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val showDialog: Boolean = false,
    val theId: String = "",
    val loginStep: Int = 0,
    val errorMessage: String? = null
)


fun Int.floorMod(mod: Int): Int {
    val m = this % mod
    return if (m < 0) m + mod else m
}

suspend fun <T> retryUntilSuccess(
    delayMillis: Long = 5000,
    onError: (String) -> Unit,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        attempt++
        try {
            val result = block()
            return result
        } catch (e: CancellationException) {
            throw e // propagate cancellation
        } catch (e: Exception) {
            // Instead of Toast, call error callback
            onError(I18n.t("error_connect_internet"))
            delay(delayMillis) // cancellation can be detected here
        }
    }
}

@Composable
fun SignupScreen(
    onBack: () -> Unit,
    onSignupSuccess: (AccountEntity) -> Unit,
    repository: FirebaseAndIndexedDBRepository
) {
    val coroutineScope = rememberCoroutineScope()
    var signupTheId by remember { mutableStateOf("") }
    var signupName by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("") }
    var signupPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val toastMessage by remember { mutableStateOf<String?>(null) }

    @NoLiveLiterals
    suspend fun requestLocationSuspend(): LocationResult =
        suspendCancellableCoroutine { cont ->
            requestLocation { result ->
                if (cont.isActive) cont.resume(result)
            }
        }

    @NoLiveLiterals
    fun handleSignup() {
        coroutineScope.launch {
            isLoading = true
            try {
                // 🔑 Wait for GPS before proceeding
                val locationResult = requestLocationSuspend()
                if (!locationResult.isOk) {
                    errorMessage = I18n.t("Grant gps access")
                    isLoading = false
                    return@launch
                }
                val latitude = locationResult.latitude!!
                val longitude = locationResult.longitude!!
                if (signupTheId.isBlank() || signupName.isBlank() || accountName.isBlank() || signupPassword.isBlank()) {
                    errorMessage = I18n.t("error_enter_all_fields")
                    isLoading = false
                    return@launch
                }
                if (signupPassword.length < 6) {
                    errorMessage = I18n.t("error_password_length")
                    isLoading = false
                    return@launch
                }
                val decryptedId = signupTheId
                if (decryptedId.length < 12) {
                    errorMessage = I18n.t("error_invalid_product_id_length")
                    isLoading = false
                    return@launch
                }
                val prefix = decryptedId.substring(0, 3)
                val numericPart = decryptedId.substring(3, 10)
                val suffixM = decryptedId.getOrNull(10)
                val suffixB = decryptedId.getOrNull(11)
                val prefixInt = numericPart.toIntOrNull()

                val isValidFormat = decryptedId.length == 12 &&
                        prefix == "999" &&
                        suffixM == 'E' &&
                        suffixB == 'M' &&
                        numericPart.all { it.isDigit() }
                if (!isValidFormat || prefixInt == null || prefixInt < 1) {
                    errorMessage = I18n.t("error_invalid_product_id")
                    isLoading = false
                    return@launch
                }

                val auth = getAuth()
                val userCredential = try {
                    createUserWithEmailAndPassword(auth, signupName, signupPassword).awaitPromise()
                } catch (e: Exception) {
                    signInWithEmailAndPassword(auth, signupName, signupPassword).awaitPromise()
                }
                val user = userCredential.user
                if (user == null) {
                    errorMessage = I18n.t("signUp_error")
                    isLoading = false
                    return@launch
                }
                val firebaseUid = user.uid
                val now = getCurrentDate()
                val alreadySignedUp = repository.checkIfProductIsAlreadySignedUp(decryptedId)
                if (alreadySignedUp) {
                    errorMessage = I18n.t("device_registered_different_account")
                    isLoading = false
                    return@launch
                }
                val existingUser = repository.getUserFromRTDB(firebaseUid)
                val updatedUser = if (existingUser != null) {
                    val currentProducts = existingUser.ownedProducts.toMutableList()
                    if (!currentProducts.contains(decryptedId)) {
                        currentProducts.add(decryptedId)
                    }
                    val reorderedProducts = listOf(decryptedId) + currentProducts.filter { it != decryptedId }
                    existingUser.copy(
                        ownedProducts = reorderedProducts,
                        loggedIntoProduct = decryptedId,
                        lastLoginRole = "user",
                        lastAccessedDate = now,
                        isLoggedIn = true
                    )
                } else {
                    UserEntity(
                        uuid = firebaseUid,
                        name = "",
                        email = signupName,
                        ownedProducts = listOf(decryptedId),
                        isLoggedIn = true,
                        loggedIntoProduct = decryptedId,
                        lastLoginRole = "user",
                        creationDate = now,
                        lastAccessedDate = now,
                        language = "English"
                    )
                }
                repository.deleteAllUsersFromSQLite()
                retryUntilSuccess(onError = { msg -> errorMessage = msg }) {
                    repository.saveUserToRTDB(updatedUser)
                }
                repository.saveUserToSQLite(updatedUser)

                val deviceRef = ref(getDatabase(), "devices/$decryptedId")
                val deviceData = js("{}").unsafeCast<dynamic>()
                deviceData.authenticated = false
                deviceData.canAccess = true
                retryUntilSuccess(onError = { msg -> errorMessage = msg }) {
                    update(deviceRef, deviceData).awaitPromise()
                }

                val newAccount = AccountEntity(
                    sn = 1L,
                    productId = prefixInt,
                    theId = signupTheId,
                    decryptedProductId = decryptedId,
                    activateDate = "",
                    primaryUuid = firebaseUid,
                    creationDate = now,
                    lastDate = "",
                    lastUpdated = 2L,
                    active = true,
                    latitude = latitude.toString(),
                    longitude = longitude.toString(),
                    address = accountName,
                    wifiName = "",
                    wifiPass = ""
                )
                repository.saveAccountToSQLite(newAccount)
                retryUntilSuccess(onError = { msg -> errorMessage = msg }) {
                    repository.saveAccountToFirebase(newAccount)
                }
                errorMessage = I18n.t("signUp_success")
                onSignupSuccess(newAccount)
            } catch (e: Exception) {
                errorMessage = I18n.t("signUp_error")
            } finally {
                isLoading = false
            }
        }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            height(100.vh)
            backgroundColor(Color(lightAppBackground))
            fontFamily("sans-serif")
        }
    }) {
        // Top bar with ground_fault_detector logo
        Div({
            style {
                width(100.percent)
                height(40.px)
                color(Color(TextColor))
                backgroundColor(Color(AppHeader))
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
            }
        }) {
            Text("SIGNUP")
        }
    // Center form
        Div({
            style {
                flex(1)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                alignItems(AlignItems.Center)
                justifyContent(JustifyContent.Center)
                gap(25.px)
                padding(24.px)
            }
        }) {
            // ID field
            Div({
                style {
                    width(90.percent)
                    maxWidth(400.px)
                    color(Color(TextColor))
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                }
            }) {
                Text("ID")
                Div({ style { height(4.px) } })
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("border-bottom", "1px solid #ccc")
                        paddingBottom(8.px)
                    }
                }) {
                    IconForPanel("id", sizePx = 24, tint = "#FFFFFF")
                    Input(type = InputType.Text) {
                        value(signupTheId)
                        onInput { ev -> signupTheId = ev.value }   // use .value
                        placeholder("Enter device ID")
                        style {
                            flex(1)
                            marginLeft(8.px)
                            border(0.px)
                            outline("none")
                            backgroundColor(Color.transparent)
                            fontSize(16.px)
                            color(Color("#BFBFBF"))
                        }
                    }
                }
            }
// Email field
            Div({
                style {
                    width(90.percent)
                    maxWidth(400.px)
                    display(DisplayStyle.Flex)
                    color(Color(TextColor))
                    flexDirection(FlexDirection.Column)
                }
            }) {
                Text("Email")
                Div({ style { height(4.px) } })
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("border-bottom", "1px solid #ccc")
                        paddingBottom(8.px)
                    }
                }) {
                    IconForPanel("mail", sizePx = 24, tint = "#FFFFFF")
                    Input(type = InputType.Text) {
                        value(signupName)
                        onInput { ev -> signupName = ev.value }   // use .value
                        placeholder("Enter your email")
                        style {
                            flex(1)
                            marginLeft(8.px)
                            border(0.px)
                            outline("none")
                            backgroundColor(Color.transparent)
                            color(Color(TextColor))
                            fontSize(16.px)
                            color(Color("#BFBFBF"))
                        }
                    }
                }
            }

            // Account Name field
            Div({
                style {
                    width(90.percent)
                    maxWidth(400.px)
                    color(Color(TextColor))
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                }
            }) {
                Text("Installation location")
                Div({ style { height(4.px) } })
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("border-bottom", "1px solid #ccc")
                        paddingBottom(8.px)
                    }
                }) {
                    IconForPanel("location", sizePx = 24, tint = "#FFFFFF")
                    Input(type = InputType.Text) {
                        value(accountName)
                        onInput { ev -> accountName = ev.value }   // use .value
                        placeholder("Enter Device location")
                        style {
                            flex(1)
                            marginLeft(8.px)
                            border(0.px)
                            outline("none")
                            backgroundColor(Color.transparent)
                            fontSize(16.px)
                            color(Color("#BFBFBF"))
                        }
                    }
                }
            }
            // Password field with visibility toggle
            Div({
                style {
                    width(90.percent)
                    maxWidth(400.px)
                    color(Color(TextColor))
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                }
            }) {
                Text("Password")
                Div({ style { height(4.px) } })
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        alignItems(AlignItems.Center)
                        property("border-bottom", "1px solid #ccc")
                        paddingBottom(8.px)
                    }
                }) {
                    IconForPanel("password", sizePx = 24, tint = "#FFFFFF")
                    Input(type = if (isPasswordVisible) InputType.Text else InputType.Password) {
                        value(signupPassword)
                        onInput { ev -> signupPassword = ev.value }   // use .value
                        placeholder("Enter your password")
                        style {
                            flex(1)
                            marginLeft(8.px)
                            border(0.px)
                            outline("none")
                            backgroundColor(Color.transparent)
                            fontSize(16.px)
                            color(Color("#BFBFBF"))
                        }
                    }
                    Button(attrs = {
                        style {
                            backgroundColor(Color.transparent)
                            border(0.px)
                            cursor("pointer")
                            marginLeft(8.px)
                        }
                        onClick { isPasswordVisible = !isPasswordVisible }
                    }) {
                        IconForPanel(if (isPasswordVisible) "visible" else "invisible", sizePx = 24, tint = "#FFFFFF")
                    }
                }
            }
            // Error message prompt
            Div({
                style {
                    height(20.px)
                    marginTop(4.px)
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.Center)
                    alignItems(AlignItems.Center)
                    color(Color(ErrorColor))
                    fontSize(14.px)
                    textAlign("center")
                }
            }) {
                errorMessage?.let { Text(it) }
            }

            // Signup button
            Button(attrs = {
                style {
                    marginTop(8.px)
                    padding(8.px, 16.px)
                    width(250.px)
                    backgroundColor(Color(theIconColor))
                    color(Color.white)
                    borderRadius(6.px)
                    fontSize(14.px)
                    cursor("pointer")
                }
                onClick { handleSignup() }
            }) {
                Text(if (isLoading) I18n.t("signing_up") else I18n.t("sign_up"))
            }

            // Back to login link
            Div({ style { marginTop(12.px) } }) {
                Button(attrs = {
                    style {
                        backgroundColor(Color.transparent)
                        border(0.px)
                        color(Color(TextColor))
                        cursor("pointer")
                        fontSize(14.px)
                    }
                    onClick { onBack() }
                }) {
                    Text(I18n.t("back_to_login"))
                }
            }

            // Toast message
            toastMessage?.let {
                Div({ classes("toast") }) { Text(it) }
            }
        }
    }
}
