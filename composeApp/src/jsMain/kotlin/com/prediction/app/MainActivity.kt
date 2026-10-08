package com.prediction.app
import kotlin.math.PI
import kotlinx.browser.window
import kotlinx.coroutines.*
import androidx.compose.runtime.*
import firebase.initializeApp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.web.css.JustifyContent
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.mutableStateOf
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.*
import kotlinx.datetime.Instant
import com.prediction.app.ui.theme.*
import firebase.auth.getAuth
import firebase.database.getDatabase
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.css.*
import org.w3c.dom.CanvasRenderingContext2D
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.dom.Input
import kotlinx.datetime.TimeZone
import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import kotlin.js.Date
import org.jetbrains.compose.web.css.color
import firebase.auth.signOut
import firebase.database.onValue
import org.w3c.dom.HTMLInputElement
import firebase.database.ref

import kotlin.math.roundToInt
import firebase.auth.setPersistence
import firebase.auth.browserLocalPersistence
import firebase.auth.onAuthStateChanged
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.selected
import org.w3c.dom.HTMLAnchorElement
import kotlin.random.Random
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.get
import org.w3c.files.File
import kotlin.js.Promise
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Headers

object ThemePreference {
    private const val KEY_DARK_MODE = "dark_mode"
    fun setDarkMode(enabled: Boolean) {
        window.localStorage.setItem(KEY_DARK_MODE, enabled.toString())
    }
    fun isDarkMode(): Boolean {
        return window.localStorage.getItem(KEY_DARK_MODE)?.toBoolean() ?: false
    }
    fun isDarkModeSet(): Boolean {
        return window.localStorage.getItem(KEY_DARK_MODE) != null
    }
}

// --- App settings state ---
object AppSettingsState {
    var isDarkMode by mutableStateOf(false)
    var currentLanguage by mutableStateOf("en")  // default language
}

sealed class Screen {
    object Loading : Screen()
    object Login : Screen()
    data class Main(val account: AccountEntity) : Screen()
}

object NetworkStatus {
    private val _isNetworkAvailable = MutableStateFlow(true)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable

    fun setNetworkAvailable(available: Boolean) {
        _isNetworkAvailable.value = available
    }
}

object AppState {
    val currentScreen = MutableStateFlow<Screen>(Screen.Loading)
    fun setScreen(screen: Screen) { currentScreen.value = screen }
}

var globalCount:Int = 0

@Composable
fun App() {
    var repository: FirebaseAndIndexedDBRepository? by remember { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        // Theme setup
        val isDarkMode = if (ThemePreference.isDarkModeSet()) ThemePreference.isDarkMode() else true
        if (!ThemePreference.isDarkModeSet()) ThemePreference.setDarkMode(isDarkMode)
        AppSettingsState.isDarkMode = isDarkMode
        AppSettingsState.currentLanguage = window.localStorage.getItem("language") ?: "en"
        // Firebase init
        val firebaseConfig = js(
            """{
                apiKey: "AIzaSyAkfPAXzoa8ye4ek44h-PPN5e50tc-GWT0",
                authDomain: "prediction-56361.firebaseapp.com",
                databaseURL: "https://prediction-56361-default-rtdb.asia-southeast1.firebasedatabase.app",
                projectId: "prediction-56361",
                storageBucket: "prediction-56361.firebasestorage.app",
                messagingSenderId: "682840244371",
                appId: "1:682840244371:web:785b0d8412317e0dbf0d66"
            }"""
        )
        val app = initializeApp(firebaseConfig)
        val repo = FirebaseAndIndexedDBRepository()
        repo.init()
        repository = repo
        val auth = getAuth(app)
        setPersistence(auth, browserLocalPersistence).then {
            console.log("[auth] Persistence set to localStorage")
        }.catch { e ->
            console.error("[auth] Persistence setup failed:", e)
        }
        onAuthStateChanged(auth) { user ->
            if (user != null) {
                console.log("[auth] Session restored for uid:", user.uid)
            } else {
                console.log("[auth] No user → offline mode")
            }
        }
        // Network listeners
        window.ononline = { NetworkStatus.setNetworkAvailable(true) }
        window.onoffline = { NetworkStatus.setNetworkAvailable(false) }
        // Startup logic
        try {
            console.log("🚀 Starting app init")
            val user = repo.getUserFromSQLite()
            console.log("user $user")
            if (user != null && user.isLoggedIn) {
                // Normal user path
                val account = initializeAppAndLogin(
                    repository = repo,
                    updateUser = { updateUser(it) },
                    updateAccountData = { updateAccountData(it) },
                )
                if (account != null) {
                    enterApp(account, repo)
                } else {
                    AppState.setScreen(Screen.Login)
                }
            } else {
                AppState.setScreen(Screen.Login)
            }
        } catch (e: dynamic) {
            console.error("🔥 INIT FAILED", e)
            AppState.setScreen(Screen.Login)
        }
    }

    // --- UI ---
    val screen by AppState.currentScreen.collectAsState()
    val scope = rememberCoroutineScope()
    when (screen) {
        is Screen.Loading -> Div { Text("Loading…") }
        is Screen.Login -> repository?.let {
            LoginSignupScreen(
                repository = it,
                onLoginSuccess = { account ->
                    scope.launch { enterApp(account, it) }
                },
                onAdminLoginSuccess = {
                    scope.launch {  }
                }
            )
        }
        is Screen.Main -> repository?.let { MainScreen(repository = it) }
    }
}

suspend fun enterApp(
    account: AccountEntity,
    repository: FirebaseAndIndexedDBRepository
) {
    try {
        val user = repository.getUserFromSQLite()
        if (user == null) {
            console.error("User not found in SQLite")
            AppState.setScreen(Screen.Login)
            return
        }
        updateUser(user)
        updateAccountData(account)
        repository.saveAccountToSQLite(account)
        AppState.setScreen(Screen.Main(account))
        loadPostLoginData(
            account = account,
            repository = repository,
        )
    } catch (e: Exception) {
        console.error("EnterApp failed", e)
    }
}

suspend fun loadPostLoginData(
    account: AccountEntity,
    repository: FirebaseAndIndexedDBRepository,
) {
    try {
        monitorSNForUpdates(repository, account.decryptedProductId)
        loadEarthingResults(account, repository)
    } catch (_: Exception) {
        // optionally log
    }
}

@NoLiveLiterals
suspend fun initializeAppAndLogin(
    repository: FirebaseAndIndexedDBRepository,
    updateUser: (UserEntity) -> Unit,
    updateAccountData: (AccountEntity) -> Unit,
): AccountEntity? {
    return try {
        val user = repository.getUserFromSQLite() ?: return null
        if (!user.isLoggedIn || user.loggedIntoProduct.isBlank()) return null

        // Admin branch
        if (user.loggedIntoProduct == "admin" && user.lastLoginRole == "admin") {
            val adminUser = user.copy(
                isLoggedIn = true,
                loggedIntoProduct = "admin",
                lastLoginRole = "admin",
                lastAccessedDate = getCurrentDateFormatted()
            )
            repository.saveUserToSQLite(adminUser)
            repository.saveUserToRTDB(adminUser)
            updateUser(adminUser)
            return null // admin path handled separately
        }

        // Normal user branch
        val allAccounts = repository.getAccountsFromSQLite()
        var account = allAccounts.find { it.decryptedProductId == user.loggedIntoProduct }
        var updatedUser = user

        if (account == null) {
            for (ownedProductId in user.ownedProducts) {
                val matchedAccount = allAccounts.find { it.decryptedProductId == ownedProductId }
                if (matchedAccount != null) {
                    val newUpdatedUser = user.copy(
                        loggedIntoProduct = matchedAccount.decryptedProductId,
                        lastLoginRole = "user",
                        lastAccessedDate = getCurrentDateFormatted(),
                        isLoggedIn = true
                    )
                    repository.saveUserToSQLite(newUpdatedUser)
                    account = matchedAccount
                    updatedUser = newUpdatedUser
                    break
                }
            }
            if (account == null) return null
        }

        updateUser(updatedUser)
        updateAccountData(account)
        account
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}


fun formatDate(ms: Long): String {
    val instant = Instant.fromEpochMilliseconds(ms)
    val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())

    if (dateTime.year < 2025) {
        return "Never"
    }
    return "${dateTime.dayOfMonth.toString().padStart(2, '0')}/" +
            "${dateTime.monthNumber.toString().padStart(2, '0')}/" +
            "${dateTime.year}"
}

fun formatTime(ms: Long): String {
    val instant = Instant.fromEpochMilliseconds(ms)
    val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())

    if (dateTime.year < 2025) {
        return "Never"
    }
    return "${dateTime.hour.toString().padStart(2, '0')}:${
        dateTime.minute.toString().padStart(2, '0')
    }"
}

fun formatDayPeriod(ms: Long): String {
    val instant = Instant.fromEpochMilliseconds(ms)
    val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())

    if (dateTime.year < 2025) {
        return "Never"
    }

    val dayOfWeek = dateTime.dayOfWeek.name.lowercase()
        .replaceFirstChar { it.uppercase() } // e.g. "Thursday"

    val tod = when (dateTime.hour) {
        in 0..5 -> "Night"
        in 6..11 -> "Morning"
        in 12..17 -> "Day"
        else -> "Evening"
    }

    return "$dayOfWeek $tod"
}


@NoLiveLiterals
private fun monitorSNForUpdates(
    repository: FirebaseAndIndexedDBRepository,
    decryptedProductId: String
) {
    val db = getDatabase()
    val accountRef = ref(db, "products/$decryptedProductId/account")
    onValue(accountRef) { snapshot ->
        try {
            val firebaseAccount = snapshot.`val`() ?: return@onValue
            val accountData = firebaseAccount.asDynamic()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    repository.syncDataFromFirebase(accountData as? AccountEntity)
                } catch (_: Exception) {
                    // swallow errors inside coroutine
                }
            }
        } catch (_: Exception) {
            // swallow errors in outer block
        }
    }
}

fun isMobileDevice(): Boolean {
    val ua = window.navigator.userAgent.lowercase()
    val mobileKeywords = listOf("android", "iphone", "ipad", "ipod", "windows phone")
    return mobileKeywords.any { ua.contains(it) }
}

fun isMobileLayout(): Boolean {
    // Require both: device is mobile AND viewport is narrow
    return isMobileDevice() || window.innerWidth < 768
}

@Composable
private fun HeaderLogo(
    isMobile: Boolean
) {
    if (isMobile) {
        Div({
            style {
                width(100.percent)
                height(30.px)
                backgroundColor(Color(lightAppHeader))
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
                flexShrink(0)
                color(Color(TextColor))
            }
        }) {
            Text("POWER USAGE FORECAST")
        }
    } else {
        Div({
            style {
                width(100.percent)
                height(30.px)
                backgroundColor(Color(lightAppHeader))
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Row)
                alignItems(AlignItems.Center)
                flexShrink(0)
            }
        }) {

            Div({
                style {
                    width(180.px)
                    height(100.percent)
                }
            })

            Div({
                style {
                    flexGrow(1)
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.Center)
                    alignItems(AlignItems.Center)
                    color(Color(TextColor))
                }
            }) {
                Text("POWER USAGE FORECAST")
            }
        }
    }
}


@Composable
fun MainScreen(
    repository: FirebaseAndIndexedDBRepository
) {
    val scope = rememberCoroutineScope()
    val showLogoutDialog = remember { mutableStateOf(false) }
    val logoutUser = remember { mutableStateOf<UserEntity?>(null) }
    val accountHere by accountState.collectAsState()
    val accountUser by userState.collectAsState()
    var updatedAccount by remember { mutableStateOf(accountHere) }
    LaunchedEffect(accountState) { updatedAccount = accountHere }
    // ✅ Use your helper
    val isMobile = remember { isMobileLayout() }
    // Navigation items (shared between mobile and desktop)
    val desktopitems = listOf(
        SidebarItem("Home", "home"),
        SidebarItem("Records", "quality"),
        SidebarItem("Forecast", "forecast"),
        SidebarItem("settings", "settings"),
    )
    val mobileitems = listOf(
        SidebarItem("Home", "home"),
        SidebarItem("Records", "quality"),
        SidebarItem("Forecast", "forecast"),
        SidebarItem("settings", "settings"),
        )
    // Track which page is currently active
    var currentPage by remember { mutableStateOf("Home") }

    fun pageTitle(): String {
        return when (currentPage.lowercase()) {
            "home" -> "Live data"
            "settings" -> "Settings"
            "records" -> "Records"
            else -> currentPage.uppercase()
        }
    }
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            property("height", "100dvh")
            overflow("hidden")
            margin(0.px)
            padding(0.px)
            backgroundColor(Color("#f5f5f5"))
            fontFamily("sans-serif")
        }
    }) {
        HeaderLogo(isMobile)

        if (isMobile) {
            Div({
                style {
                    flexGrow(1)
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    minHeight(0.px)
                    position(Position.Relative)
                    overflow("hidden")
                }
            }) {
                // top bar
                Div({
                    style {
                        width(100.percent)
                        height(50.px)
                        display(DisplayStyle.Flex)
                        justifyContent(JustifyContent.SpaceBetween)
                        alignItems(AlignItems.Center)
                        backgroundColor(Color(lightAppHeader))
                        boxSizing("border-box")
                        flexShrink(0)
                        flexGrow(0)
                        justifyContent(JustifyContent.Center)
                        alignItems(AlignItems.Center)
                        fontSize(18.px)
                        fontWeight("bold")
                        color(Color(TextColor))
                    }
                }) {
                    Text(pageTitle())
                }
                // content (IMPORTANT FIX)
                Div({
                    style {
                        flexGrow(1)
                        minHeight(0.px)
                        overflowY("auto")
                        backgroundColor(Color(lightAppBackground))
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        // reserve space for bottom bar
                        paddingBottom(60.px) // same as bottom bar height
                        boxSizing("border-box")
                    }
                }) {
                    MainContent(
                        currentPage = currentPage,
                        repository = repository,
                        onPageChange = { currentPage = it },
                        isMobile = isMobile
                    )
                }
            }

            // bottom nav (fixed, OUTSIDE flow is correct)
            Div({
                style {
                    position(Position.Fixed)
                    bottom(0.px)
                    width(100.percent)
                    height(70.px)

                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceAround)
                    alignItems(AlignItems.Center)

                    backgroundColor(Color(AppHeader))
                    property("border-radius", "20px 20px 0 0")
                    property("box-shadow", "0px -2px 10px rgba(0,0,0,0.1)")
                }
            }) {
                mobileitems.forEach { item ->
                    BottomNavItemView(item, currentPage) { selected ->
                        currentPage = selected
                    }
                }
            }
        }

        // ================= DESKTOP =================
        else {

            Div({
                style {
                    flexGrow(1)
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Row)

                    height(100.percent)
                    overflow("hidden")
                }
            }) {

                // ========== SIDEBAR ==========
                Div({
                    style {
                        width(180.px)
                        height(100.percent)
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)
                        backgroundColor(Color(barColor))
                        boxSizing("border-box")
                        overflow("hidden")
                        flexShrink(0)   // 🔑 prevent shrinking
                        flexGrow(0)     // 🔑 prevent growing
                        flexBasis(180.px) // 🔑 fix the basis to 180px
                    }
                }) {
                    // HEADER
                    Div({
                        style {
                            width(100.percent)
                            //height(8.vh)
                            height(50.px)
                            //maxHeight(100.px)
                            display(DisplayStyle.Flex)
                            justifyContent(JustifyContent.Center)
                            alignItems(AlignItems.Center)

                            flexShrink(0)
                        }
                    }) {
                        Span({
                            style {
                                color(Color("#FFFFFF"))
                                fontSize(18.px)
                                fontWeight("bold")
                            }
                        }) { Text("Control Panel") }
                    }

                    // SCROLL AREA (KEY FIX)
                    Div({
                        style {
                            flexGrow(1)
                            minHeight(0.px)
                            overflowY("auto")
                            overflowX("hidden")
                            display(DisplayStyle.Flex)
                            flexDirection(FlexDirection.Column)
                        }
                    }) {
                        desktopitems.forEach { item ->
                            Div({
                                style {
                                    width(100.percent)
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    justifyContent(JustifyContent.FlexStart)
                                    flexGrow(1)            // allow row to expand
                                    minHeight(45.px)       // minimum size
                                    maxHeight(80.px)       // cap size on large screens
                                    cursor("pointer")
                                }
                                onClick { currentPage = item.key }
                            }) {
                                SidebarItemView(item, currentPage) { selected ->
                                    currentPage = selected
                                }
                            }
                        }
                        // Divider
                        Div({
                            style {
                                width(100.percent)
                                height(1.px)
                                backgroundColor(Color("#CCCCCC"))
                                marginTop(8.px)
                                marginBottom(8.px)
                                flexShrink(0)
                            }
                        })
                        // Logout as a normal scrollable item
                        Div({
                            style {
                                flexShrink(0)             // don’t stretch, keep natural height
                                minHeight(50.px)
                                maxHeight(60.px)
                                display(DisplayStyle.Flex)
                                alignItems(AlignItems.Center)
                                justifyContent(JustifyContent.FlexStart)
                                cursor("pointer")
                                fontFamily("sans-serif")
                                fontSize(16.px)
                                paddingLeft(12.px)
                                gap(8.px)
                                backgroundColor(Color(barColor))
                                color(Color("#E53935"))   // red text for logout
                            }
                            onClick {
                                showLogoutDialog.value = true
                                logoutUser.value = accountUser
                            }
                        }) {
                            IconForPanel("logout", 24, altText = "logout icon", tint = "#E53935")
                            Text("Logout")
                        }
                    }
                }

                // ========== MAIN ==========
                Div({
                    style {
                        flexGrow(1)
                        minHeight(0.px)
                        position(Position.Relative)
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Column)

                        overflow("hidden")
                        backgroundColor(Color(lightAppBackground))
                    }
                }) {
                    Div({
                        style {
                            width(100.percent)
                            height(50.px)
                            display(DisplayStyle.Flex)
                            alignItems(AlignItems.Center)
                            justifyContent(JustifyContent.SpaceBetween)
                            backgroundColor(Color(lightAppHeader))
                            flexShrink(0)
                            flexGrow(0)
                            justifyContent(JustifyContent.Center)
                            alignItems(AlignItems.Center)
                            fontSize(18.px)
                            fontWeight("bold")
                            color(Color(TextColor))
                        }
                    }) {
                        Text(pageTitle())
                    }

                    Div({
                        style {
                            flexGrow(1)
                            minHeight(0.px)
                            overflowY("auto")

                            display(DisplayStyle.Flex)
                            flexDirection(FlexDirection.Column)
                        }
                    }) {
                        MainContent(
                            currentPage = currentPage,
                            repository = repository,
                            onPageChange = { currentPage = it },
                            isMobile = isMobile
                        )
                    }
                }
            }
        }
    }

// Logout dialog
    logoutUser.value?.let { user ->
        if (showLogoutDialog.value) {
            LogoutDialog(
                onConfirmLogout = {
                    showLogoutDialog.value = false
                    scope.launch {
                        val currentUser = accountUser
                        currentUser?.let {
                            val updatedUser = it.copy(isLoggedIn = false)
                            updateUser(updatedUser)
                            repository.saveUserToSQLite(updatedUser)
                            repository.saveUserToRTDB(updatedUser)
                            val auth = getAuth()
                            signOut(auth).then {
                                window.location.reload()
                            }.catch {
                                window.location.reload()
                            }
                        }
                    }
                },
                onDismiss = { showLogoutDialog.value = false }
            )
        }
    }
}


@Composable
fun MainContent(
    currentPage: String,
    repository: FirebaseAndIndexedDBRepository,
    onPageChange: (String) -> Unit,
    isMobile: Boolean
) {
    val accountHere by accountState.collectAsState()
    val accountUser by userState.collectAsState()
    val earthingResults by earthingResult.collectAsState()

    val scope = rememberCoroutineScope()
    val safeAccount = accountHere ?: return
    val safeUser = accountUser ?: return

    @Composable
    fun PageBody() {
        when (currentPage.lowercase()) {
            "home" -> {
                homepage(
                    productId = accountHere!!.decryptedProductId,
                    account = safeAccount,
                    reading = earthingResults,
                    repository = repository
                )
            }
            "records" -> {
                RecordsPage(
                    productId = accountHere!!.decryptedProductId,
                    account = safeAccount,
                    reading = earthingResults,
                    onBack = { onPageChange("home") },
                    repository = repository
                )
            }
            "forecast" -> {
                ForecastPage(
                    productId = accountHere!!.decryptedProductId,
                    account = safeAccount,
                    reading = earthingResults,
                    onBack = { onPageChange("home") },
                    repository = repository
                )
            }
            "settings" -> SettingsPage(
                account = safeAccount,
                accountUser = safeUser,
                onCancel = { onPageChange("home") },
                onSave = { newAccount, updatedUser ->
                    updateAccountData(newAccount)
                    scope.launch {
                        repository.saveAccountToSQLite(newAccount)
                        repository.saveUserToRTDB(updatedUser)
                        repository.saveAccountToFirebase(newAccount)
                        repository.saveUserToSQLite(updatedUser)
                    }
                    onPageChange("home")
                }
            )
            else -> Text("Page not found: $currentPage")
        }
    }
    Div({
        style {
            flexGrow(1)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color(lightAppBackground))
            height(100.percent)
            property("overflow", "hidden")
            boxSizing("border-box")
        }
    }) {
        Div({
            style {
                flexGrow(1)
                property("min-height", "0")
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                paddingLeft(if (isMobile) 1.px else 12.px)
                paddingRight(if (isMobile) 1.px else 12.px)
                boxSizing("border-box")
            }
        }) {
            PageBody()
        }
    }
}

fun Double.oneDecimal(): String = ((this * 10).roundToInt() / 10.0).toString()
fun Float.oneDecimal(): String = toDouble().oneDecimal()


@NoLiveLiterals
@Composable
fun homepage(
    productId: String,
    account: AccountEntity,
    reading: EarthingReading,
    repository: FirebaseAndIndexedDBRepository
) {
    Div({
        style {
            width(100.percent)
            height(100.percent)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            overflowY("auto")
            gap(8.px)
            padding(8.px)
            boxSizing("border-box")
        }
    }) {
        @Composable
        fun CategorySection(title: String, cards: List<Pair<String, String>>) {
            Div({
                style {
                    border(1.px, LineStyle.Solid, Color.lightgray) // outer category border
                    borderRadius(8.px)
                    padding(12.px)
                }
            }) {
                // Heading with original styling
                Div({
                    style {
                        fontWeight("bold")
                        fontSize(16.px)
                        color(Color.white)   // restored original color
                        marginBottom(4.px)
                    }
                }) { Text(title) }

                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexWrap(FlexWrap.Wrap)
                        gap(16.px)
                    }
                }) {
                    cards.forEach { (label, value) ->
                        Div({
                            style {
                                flex("1 1 auto")
                                minWidth(100.px) // smaller minimum width
                                height(55.px)
                                border(1.px, LineStyle.Solid, Color.white) // white border
                                borderRadius(12.px)
                                display(DisplayStyle.Flex)
                                flexDirection(FlexDirection.Column)
                                alignItems(AlignItems.Center)
                                padding(4.px)
                                paddingTop(4.px)
                                backgroundColor(Color("black"))
                            }
                        }) {
                            // Label with original color
                            Div({
                                style {
                                    fontSize(14.px)
                                    color(Color("#D6E2F0")) // restored original label color
                                }
                            }) { Text(label) }

                            // Value with original styling
                            Div({
                                style {
                                    fontSize(28.px)
                                    fontWeight("900")
                                    color(Color.white) // restored original value color
                                    flexGrow(1)
                                    display(DisplayStyle.Flex)
                                    alignItems(AlignItems.Center)
                                    justifyContent(JustifyContent.Center)
                                }
                            }) { Text(value) }
                        }
                    }
                }
            }
        }

        // Sections
        CategorySection("Electrical Parameters", listOf(
            "Voltage (V)" to "${reading.voltage.oneDecimal()}",
            "Current (A)" to "${reading.current.oneDecimal()}",
            "Active Power (W)" to "${reading.active_power.oneDecimal()}",
            "Energy Consumption (kWh)" to "${reading.energy_consumption.oneDecimal()}",
            "Power Factor" to "${reading.power_factor.oneDecimal()}",
            "Frequency (Hz)" to "${reading.frequency}",
            "Demand (VA)" to "${reading.demand_kWh.oneDecimal()}"

        ))

        CategorySection("Environmental Parameters", listOf(
            "Temperature (°C)" to "${reading.temperature.oneDecimal()}",
            "Humidity (%)" to "${reading.humidity.oneDecimal()}",
        ))

        CategorySection("Time parameters", listOf(
            "Date" to formatDate(reading.timestamp * 1000),
            "Time" to formatTime(reading.timestamp * 1000),
            "Day Period" to formatDayPeriod(reading.timestamp * 1000)
        ))

        CategorySection("Socio-Economic Parameters", listOf(
            "Building Type" to reading.building_type,
            "Occupancy Level" to reading.occupancy_level.toString(),
            "Operational Schedule" to reading.operational_schedule,
            "Electricity Tariff" to reading.electricity_tariff,
            "Appliance Category" to reading.appliance_category
        ))
    }
}



@NoLiveLiterals
@Composable
fun RecordsPage(
    productId: String,
    account: AccountEntity,
    reading: EarthingReading,
    onBack: () -> Unit,
    repository: FirebaseAndIndexedDBRepository
) {
    var forecastData by remember { mutableStateOf<List<ForecastRecord>>(emptyList()) }
    var selectedParameter by remember { mutableStateOf(DEMAND_PARAM) }
    var showGraph by remember { mutableStateOf(true) }
    var graphData by remember { mutableStateOf<List<liveData>>(emptyList()) }
    var canvasRef: HTMLCanvasElement? = null
    var selectedInterval by remember { mutableStateOf("Hourly") }
    // Live view: now sits in the middle, measured readings on the left,
    // the server's automatic forecasts (15-minute steps) on the right
    val LIVE_HALF_MS = 3 * 3600000.0
    var now = js("Date.now()") as Double
    var startTime by remember {
        mutableStateOf(now - LIVE_HALF_MS)
    }
    var endTime by remember {
        mutableStateOf(now + LIVE_HALF_MS)
    }
    val HOUR_MS = 3600000.0
    val DAY_MS = 86400000.0
    // True while showing the rolling "around now" window; navigating turns it off
    // so new readings no longer yank the view back to the present
    var followLive by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }

    fun centreOnNow() {
        now = js("Date.now()") as Double
        startTime = now - LIVE_HALF_MS
        endTime = now + LIVE_HALF_MS
        refreshTick++
    }

    fun updateRange(interval: String, forward: Boolean) {
        followLive = false
        val step = if (forward) 1 else -1
        val d = Date(startTime)
        startTime = when (interval) {
            "Hourly" -> Date(d.getFullYear(), d.getMonth(), d.getDate(), d.getHours() + step, 0, 0, 0).getTime()
            "Daily" -> Date(d.getFullYear(), d.getMonth(), d.getDate() + step, 0, 0, 0, 0).getTime()
            "Monthly" -> Date(d.getFullYear(), d.getMonth() + step, 1, 0, 0, 0, 0).getTime()
            "Yearly" -> Date(d.getFullYear() + step, 0, 1, 0, 0, 0, 0).getTime()
            else -> startTime
        }
        val s = Date(startTime)
        endTime = when (interval) {
            "Hourly" -> startTime + HOUR_MS - 1
            "Daily" -> startTime + DAY_MS - 1
            "Monthly" -> Date(s.getFullYear(), s.getMonth() + 1, 0, 23, 59, 59, 999).getTime()
            "Yearly" -> Date(s.getFullYear(), 11, 31, 23, 59, 59, 999).getTime()
            else -> startTime
        }
        // No clamping to "now": future windows are where the forecasts are
    }


    LaunchedEffect(reading){
        delay(500)
        // While showing the live window, follow new readings (pulls only what is new)
        if (followLive) centreOnNow()
    }
    // Keep the live window sliding with the clock, even between readings
    LaunchedEffect(followLive) {
        while (followLive) {
            delay(60_000)
            if (followLive) centreOnNow()
        }
    }
    LaunchedEffect(startTime, endTime, selectedInterval, refreshTick) {
        // Displaying data: the server pulls only readings newer than its last one, retrains
        // if any arrived and refreshes the forecasts, then we read the window
        val data = repository.getLiveData(account, startTime.toLong(), endTime.toLong())
        graphData = data

        // Forecasts stored on the server for this window
        forecastData = repository.getForecasts(startTime, endTime)

        println("Graph window $startTime -> $endTime: ${data.size} readings, ${forecastData.size} forecasts")
    }

    fun formatWebTime(ms: Double): String {
        val d = js("new Date(ms)") as Date
        return d.toLocaleString("en-GB") // adjust locale/format as needed
    }

    Div({
        style {
            width(100.percent)
            height(100.percent)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            overflowY("auto")
            gap(8.px)
            paddingLeft(if(isMobileLayout()) 2.px else 16.px)
            paddingRight(if(isMobileLayout()) 2.px else 16.px)
            boxSizing("border-box")
        }
    }) {
        // Header with dynamic time range
        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.SpaceBetween) // label left, button right
                alignItems(AlignItems.Center)
                fontWeight("bold")
                fontSize(18.px)
                color(Color.white)
                marginBottom(12.px)
            }
        }) {
            // Button on the extreme right
            Button(attrs = {
                style {
                    padding(6.px, 12.px)
                    borderRadius(8.px)
                    backgroundColor(Color("#164C83"))
                    color(Color.white)
                    cursor("pointer")
                }
                onClick { showGraph = !showGraph }
            }) {
                Text(if (showGraph) "Table View" else "Graph View")
            }
        }

        Div({
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)   // stack vertically
                alignItems(AlignItems.Center)         // center everything
                gap(8.px)
                marginBottom(0.px)
            }
        }) {
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.Center)
                    gap(12.px)
                }
            }) {
                listOf("Hourly", "Daily", "Monthly", "Yearly").forEach { interval ->
                    Div({
                        style {
                            padding(8.px, 16.px)
                            borderRadius(20.px)
                            property("border", "1px solid #D6E2F0")
                            cursor("pointer")
                            backgroundColor(if (selectedInterval == interval) Color("#164C83") else Color.transparent)
                            color(if (selectedInterval == interval) Color.white else Color("#D6E2F0"))
                        }

                        onClick {
                            if (selectedInterval == interval) return@onClick

                            followLive = false
                            val previous = selectedInterval
                            selectedInterval = interval
                            val d = Date(startTime)

                            when {
                                previous == "Yearly" && interval == "Monthly" -> {
                                    startTime = Date(d.getFullYear(), 0, 1, 0, 0, 0, 0).getTime()
                                    val s = Date(startTime)
                                    endTime = Date(s.getFullYear(), s.getMonth() + 1, 0, 23, 59, 59, 999).getTime()
                                }

                                previous == "Monthly" && interval == "Daily" -> {
                                    startTime = Date(d.getFullYear(), d.getMonth(), 1, 0, 0, 0, 0).getTime()
                                    endTime = startTime + DAY_MS - 1
                                }

                                previous == "Daily" && interval == "Hourly" -> {
                                    startTime = Date(d.getFullYear(), d.getMonth(), d.getDate(), 0, 0, 0, 0).getTime()
                                    endTime = startTime + HOUR_MS - 1
                                }

                                interval == "Hourly" -> {
                                    startTime = Date(d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), 0, 0, 0).getTime()
                                    endTime = startTime + HOUR_MS - 1
                                }

                                interval == "Daily" -> {
                                    startTime = Date(d.getFullYear(), d.getMonth(), d.getDate(), 0, 0, 0, 0).getTime()
                                    endTime = startTime + DAY_MS - 1
                                }

                                interval == "Monthly" -> {
                                    startTime = Date(d.getFullYear(), d.getMonth(), 1, 0, 0, 0, 0).getTime()
                                    val s = Date(startTime)
                                    endTime = Date(s.getFullYear(), s.getMonth() + 1, 0, 23, 59, 59, 999).getTime()
                                }

                                interval == "Yearly" -> {
                                    startTime = Date(d.getFullYear(), 0, 1, 0, 0, 0, 0).getTime()
                                    val s = Date(startTime)
                                    endTime = Date(s.getFullYear(), 11, 31, 23, 59, 59, 999).getTime()
                                }
                            }
                        }

                    }) {
                        Text(interval)
                    }
                }
            }

            Div({
                style {
                    marginTop(0.px)
                    fontSize(14.px)
                    color(Color("white"))
                    fontWeight("bold")
                }
            }) {
                Text(
                    if (followLive) "LIVE: last 3 hours measured, next 3 hours forecast"
                    else "${formatWebTime(startTime)} TO ${formatWebTime(endTime)}"
                )
            }
        }

        Div({
            style {
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
                gap(8.px)
                marginBottom(0.px)
            }
        }) {
            Button(attrs = {
                onClick { updateRange(selectedInterval, forward = false) }
            }) {
                Text("◀ Prev")
            }

            Select(attrs = {
                attr("value", selectedParameter)
                onChange {
                    selectedParameter = it.target.asDynamic().value as String
                }
                style {
                    padding(6.px, 12.px)
                    borderRadius(8.px)
                    backgroundColor(Color("#164C83"))
                    color(Color.white)
                    cursor("pointer")
                }
            }) {
                listOf(
                    "Voltage",
                    "Current",
                    "Active Power",
                    "Energy Consumption",
                    "Power Factor",
                    "Frequency",
                    "Temperature",
                    "Humidity",
                    "Light Intensity",
                    DEMAND_PARAM
                ).forEach { parameter ->
                    Option(value = parameter, attrs = { if (parameter == selectedParameter) selected() }) {
                        Text(parameter)
                    }
                }
            }

            Button(attrs = {
                onClick { updateRange(selectedInterval, forward = true) }
            }) {
                Text("Next ▶")
            }

            Button(attrs = {
                style {
                    backgroundColor(if (followLive) Color("#0A7E07") else Color("#164C83"))
                    color(Color.white)
                    borderRadius(8.px)
                    padding(6.px, 12.px)
                    cursor("pointer")
                }
                onClick {
                    followLive = true
                    centreOnNow()
                }
            }) {
                Text("● Live")
            }
        }

        if (showGraph) {
            PlotGraph(
                data = graphData,
                forecast = forecastData,
                interval = selectedInterval,
                parameter = selectedParameter,
                startTime = startTime,
                endTime = endTime
            )
        } else {
            RecordsTable(
                data = graphData,
                forecast = forecastData,   // ✅ new parameter
                account = account,
                startTime = startTime,
                endTime = endTime
            )
        }
    }
}


@NoLiveLiterals
data class ForecastRecord(
    val sn: Int,
    val timestamp: Int,
    val predXgb: Double,
    val predRf: Double
)

@Composable
@NoLiveLiterals
fun ForecastPage(
    productId: String,
    account: AccountEntity,
    reading: EarthingReading,
    onBack: () -> Unit,
    repository: FirebaseAndIndexedDBRepository
) {
    // Default to the next full hour, in local time
    val next = Date(Date.now() + 3600000.0)
    var forecastDate by remember {
        mutableStateOf(
            "${next.getFullYear()}-${(next.getMonth() + 1).toString().padStart(2, '0')}-${next.getDate().toString().padStart(2, '0')}"
        )
    }
    var forecastTime by remember { mutableStateOf("${next.getHours().toString().padStart(2, '0')}:00") }

    var forecastResult by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshStatus by remember { mutableStateOf("") }

    var modelSummary by remember { mutableStateOf("") }

    // Forecast records shown in the table. The server stores every forecast it makes:
    // the next 24h after each retrain, plus any moment asked for here.
    val records = remember { mutableStateListOf<ForecastRecord>() }

    suspend fun showUpcoming() {
        // Bring the server up to date with ThingSpeak before showing its forecasts
        repository.syncNewData(account)
        val now = Date.now()
        val upcoming = repository.getForecasts(now, now + 24 * 3600000.0)
        records.clear()
        records.addAll(upcoming)
        modelSummary = repository.getModelSummary()
    }

    LaunchedEffect(Unit) { showUpcoming() }

    @Composable
    fun ActionButton(label: String, color: String, enabled: Boolean, onClick: () -> Unit) {
        Button(attrs = {
            style {
                padding(12.px, 24.px)
                borderRadius(8.px)
                backgroundColor(Color(color))
                color(Color.white)
                cursor(if (enabled) "pointer" else "not-allowed")
                opacity(if (enabled) 1.0 else 0.6)
            }
            if (!enabled) disabled()
            onClick { onClick() }
        }) {
            Text(label)
        }
    }

    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            width(100.percent)
            height(100.percent)
            backgroundColor(Color(lightAppBackground))
            color(Color.white)
            alignItems(AlignItems.Center)
            padding(6.px)
            overflowY("auto")
        }
    }) {
        // Inputs: only date and time
        Div({
            style {
                display(DisplayStyle.Flex)
                flexWrap(FlexWrap.Wrap)
                justifyContent(JustifyContent.Center)
                gap(16.px)
                width(100.percent)
            }
        }) {
            CardInput("Date", forecastDate, InputType.Date) { forecastDate = it }
            CardInput("Time", forecastTime, InputType.Time) { forecastTime = it }
        }

        Div({
            style {
                marginTop(24.px)
                display(DisplayStyle.Flex)
                flexWrap(FlexWrap.Wrap)
                justifyContent(JustifyContent.Center)
                gap(16.px)
            }
        }) {
            ActionButton(if (isLoading) "Forecasting..." else "Forecast", "#164C83", !isLoading && !isRefreshing) {
                CoroutineScope(Dispatchers.Default).launch {
                    isLoading = true
                    try {
                        // "YYYY-MM-DDTHH:MM" without a zone is parsed as local time in every browser
                        val ms = Date("${forecastDate}T$forecastTime").getTime()
                        if (ms.isNaN()) {
                            forecastResult = "Invalid date or time"
                            return@launch
                        }
                        val timestamp = (ms / 1000).toInt()

                        // The forecast is time based: the server uses the stored history up to now
                        val payload: dynamic = js("{}")
                        payload.timestamp = timestamp

                        val headers = Headers()
                        headers.append("Content-Type", "application/json")

                        val response = window.fetch(
                            "$PREDICTION_API/predict",
                            RequestInit(method = "POST", headers = headers, body = JSON.stringify(payload))
                        ).await()

                        val responseText = response.text().await()
                        if (!response.ok) {
                            forecastResult = "Forecast failed (${response.status}): $responseText"
                            return@launch
                        }

                        val json = JSON.parse<dynamic>(responseText)
                        val rec = ForecastRecord(
                            sn = 0,
                            timestamp = timestamp,
                            predXgb = (json.predicted_demand_kWh_xgb as Number).toDouble(),
                            predRf = (json.predicted_demand_kWh_rf as Number).toDouble()
                        )

                        // The server stored it; keep the offline copy in step
                        repository.saveForecastRecord(rec)

                        // Re-forecasting the same time replaces the old value
                        records.removeAll { it.timestamp == timestamp }
                        records.add(rec)
                        records.sortBy { it.timestamp }
                        forecastResult = "Saved: XGBoost ${rec.predXgb}, RandomForest ${rec.predRf}"
                    } catch (e: Throwable) {
                        forecastResult = e.message ?: "Unknown error"
                    } finally {
                        isLoading = false
                    }
                }
            }

            ActionButton("View All", "#164C83", !isRefreshing) {
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val all = repository.getForecasts(0.0, Date.now() + 365 * 86400000.0)
                        records.clear()
                        records.addAll(all)
                    } catch (e: Throwable) {
                        console.error("❌ Error loading forecasts: ${e.message}")
                    }
                }
            }

            ActionButton(if (isRefreshing) "Refreshing..." else "Refresh All", "#A33A00", !isRefreshing && !isLoading) {
                val ok = window.confirm(
                    "This deletes all training data on the server, reloads every reading from ThingSpeak " +
                            "and retrains the models. Continue?"
                )
                if (!ok) return@ActionButton
                CoroutineScope(Dispatchers.Default).launch {
                    isRefreshing = true
                    try {
                        val error = repository.refreshAll(account) { refreshStatus = it }
                        if (error != null) refreshStatus = "Refresh failed: $error" else showUpcoming()
                    } finally {
                        isRefreshing = false
                    }
                }
            }
        }

        listOf(modelSummary, refreshStatus, forecastResult).filter { it.isNotEmpty() }.forEach { msg ->
            Div({
                style {
                    marginTop(12.px)
                    fontSize(14.px)
                    textAlign("center")
                }
            }) { Text(msg) }
        }

        // Forecast records table
        if (records.isNotEmpty()) {
            Table({
                style {
                    width(100.percent)
                    property("border-collapse", "collapse")
                    color(Color.white)
                    border(1.px, LineStyle.Solid, Color("#ccc"))
                    marginTop(24.px)
                }
            }) {
                Thead {
                    Tr {
                        listOf("SN", "Date", "Time", "XGBoost (VA)", "RandomForest (VA)")
                            .forEach { header ->
                                Th({
                                    style {
                                        border(1.px, LineStyle.Solid, Color("#ccc"))
                                        padding(6.px)
                                        textAlign("center")
                                    }
                                }) { Text(header) }
                            }
                    }
                }
                Tbody {
                    records.forEachIndexed { index, rec ->
                        val d = Date(rec.timestamp.toDouble() * 1000)
                        val dateStr = "${d.getDate().toString().padStart(2,'0')}/${(d.getMonth()+1).toString().padStart(2,'0')}/${d.getFullYear()}"
                        val timeStr = "${d.getHours().toString().padStart(2,'0')}:${d.getMinutes().toString().padStart(2,'0')}"

                        Tr {
                            listOf(
                                (index + 1).toString(),
                                dateStr,
                                timeStr,
                                rec.predXgb.toString(),
                                rec.predRf.toString()
                            ).forEach { cell ->
                                Td({
                                    style {
                                        border(1.px, LineStyle.Solid, Color("#ccc"))
                                        padding(6.px)
                                        textAlign("center")
                                    }
                                }) { Text(cell) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CardInput(
    label: String,
    value: String,
    type: InputType<String>,
    onChange: (String) -> Unit
) {
    val isDateTime = type == InputType.Date || type == InputType.Time
    Div({
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            alignItems(AlignItems.Center)
            backgroundColor(Color.black)
            border(1.px, LineStyle.Solid, Color.white)
            borderRadius(6.px)
            padding(6.px)
            width(if (isDateTime) 130.px else 100.px)
        }
    }) {
        Text(label)
        Input(type = type, attrs = {
            value(value)
            onInput { onChange(it.value) }
            style {
                width(if (isDateTime) 120.px else 100.px)
                padding(4.px)
                borderRadius(4.px)
                border(1.px, LineStyle.Solid, Color.gray)
                if (!isDateTime) {
                    backgroundColor(Color.black)
                    color(Color.white)
                    textAlign("center")
                }
            }
        })
    }
}

fun getSaveExcelToWeb(fileName: String): suspend (ByteArray) -> Unit = { byteArray ->
    console.log("SAVE START bytes=${byteArray.size}")
    val blob = Blob(
        arrayOf(byteArray),
        BlobPropertyBag(
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    )
    console.log("BLOB CREATED size=${blob.size}")
    val url = URL.createObjectURL(blob)
    console.log("URL CREATED")
    val link = document.createElement("a") as HTMLAnchorElement
    link.href = url
    link.download = fileName
    document.body?.appendChild(link)
    console.log("LINK READY")
    link.click()
    console.log("CLICK DONE")
    document.body?.removeChild(link)
    console.log("LINK REMOVED")
}



@NoLiveLiterals
@Composable
fun RecordsTable(
    data: List<liveData>,
    forecast: List<ForecastRecord>,   // ✅ add forecast list
    account: AccountEntity,
    startTime: Double,
    endTime: Double
) {
    var exportTrigger by remember { mutableStateOf(false) }

    // Export trigger effect
    LaunchedEffect(exportTrigger) {
        if (exportTrigger) {
            exportTrigger = false
            val fileName = "prediction_${account.productId}_${js("Date.now()")}.xlsx"
            exportpredictionToExcel(
                forecast = forecast,
                readings = data,
                startTime = startTime,
                endTime = endTime,
                fileWriter = getSaveExcelToWeb(fileName)
            )
        }
    }

    // Merge live + forecast into one timeline
    val merged = remember(data, forecast) {
        val liveRows = data.map { item ->
            val millis = item.timestamp.toDouble() * 1000
            val d = Date(millis.toLong())
            val dateStr = "${d.getDate().toString().padStart(2,'0')}/${(d.getMonth()+1).toString().padStart(2,'0')}/${d.getFullYear()}"
            val timeStr = "${d.getHours().toString().padStart(2,'0')}:${d.getMinutes().toString().padStart(2,'0')}"
            listOf(
                dateStr, timeStr,
                item.voltage.toString(),
                item.current.toString(),
                item.active_power.toString(),
                item.energy_consumption.toString(),
                item.power_factor.toString(),
                item.temperature.toString(),
                item.humidity.toString(),
                item.frequency.toString(),
                item.building_type,
                item.occupancy_level.toString(),
                item.operational_schedule,
                item.electricity_tariff,
                item.appliance_category,
                item.demand_kWh.toString(),
                "-", "-",    // forecast columns empty
                item.timestamp.toString(), // hidden timestamp for sorting
                "live"
            )
        }

        val forecastRows = forecast.map { rec ->
            val millis = rec.timestamp.toDouble() * 1000
            val d = Date(millis.toLong())
            val dateStr = "${d.getDate().toString().padStart(2,'0')}/${(d.getMonth()+1).toString().padStart(2,'0')}/${d.getFullYear()}"
            val timeStr = "${d.getHours().toString().padStart(2,'0')}:${d.getMinutes().toString().padStart(2,'0')}"
            listOf(
                dateStr, timeStr,
                "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-",
                "-", // demand VAR not applicable
                rec.predXgb.toString(),
                rec.predRf.toString(),
                rec.timestamp.toString(), // hidden timestamp for sorting
                "forecast"
            )
        }
        (liveRows + forecastRows).sortedWith(compareBy({ it[it.size-2].toLong() }, { it.last() }))
    }

    // Download button row above the table
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.FlexEnd)
            marginBottom(8.px)
        }
    }) {
        Button(attrs = {
            style {
                padding(4.px)
                borderRadius(6.px)
                backgroundColor(Color("#164C83"))
                color(Color.white)
                cursor("pointer")
            }
            onClick { exportTrigger = true }
        }) {
            IconForPanel("download")
        }
    }

    Table({
        style {
            width(100.percent)
            property("border-collapse", "collapse")
            color(Color.white)
            border(1.px, LineStyle.Solid, Color("#ccc"))
        }
    }) {
        Thead {
            Tr {
                listOf(
                    "Date", "Time", "Voltage", "Current", "Active Power",
                    "Energy Consumption", "Power Factor", "Temperature",
                    "Humidity", "Frequency", "Building Type",
                    "Occupancy Level", "Operational Schedule",
                    "Electricity Tariff", "Appliance Category", "Demand (VA)",
                    "XGBoost Forecast (VA)", "RandomForest Forecast (VA)"
                ).forEach { header ->
                    Th({
                        style {
                            border(1.px, LineStyle.Solid, Color("#ccc"))
                            padding(6.px)
                            textAlign("center")
                        }
                    }) { Text(header) }
                }
            }
        }
        Tbody {
            merged.forEach { row ->
                Tr {
                    row.dropLast(2).forEach { cell ->   // drop hidden timestamp + type
                        Td({
                            style {
                                border(1.px, LineStyle.Solid, Color("#ccc"))
                                padding(6.px)
                                textAlign("center")
                            }
                        }) { Text(cell) }
                    }
                }
            }
        }
    }
}



@JsModule("xlsx")
@JsNonModule
external object XLSX {
    val utils: XlsxUtils
    fun writeFile(workbook: dynamic, fileName: String)
    fun write(workbook: dynamic, options: dynamic): dynamic  // <-- add this
}

external interface XlsxUtils {
    fun aoa_to_sheet(data: dynamic): dynamic
    fun book_new(): dynamic
    fun book_append_sheet(workbook: dynamic, sheet: dynamic, name: String)
}


fun Uint8Array.toByteArray(): ByteArray {
    val array = ByteArray(this.length)
    for (i in 0 until this.length) {
        array[i] = this[i]
    }
    return array
}


@NoLiveLiterals
suspend fun exportpredictionToExcel(
    readings: List<liveData>,
    forecast: List<ForecastRecord>,   // ✅ include forecast
    startTime: Double,
    endTime: Double,
    fileWriter: suspend (ByteArray) -> Unit
) {
    try {
        val workbook = XLSX.utils.book_new()

        val titleRow = mutableListOf<Any>(
            "Prediction records from ${
                Date(startTime).toLocaleString("en-GB")
            } to ${
                Date(endTime).toLocaleString("en-GB")
            }"
        )

        val header = mutableListOf<Any>(
            "Date","Time","Voltage","Current","Active Power",
            "Energy Consumption","Power Factor","Temperature","Humidity","Frequency",
            "Building Type","Occupancy Level","Operational Schedule","Electricity Tariff",
            "Appliance Category","Demand (VA)",
            "XGBoost Forecast (VA)","RandomForest Forecast (VA)"
        )

        val data = mutableListOf<Array<Any>>()
        data.add(titleRow.toTypedArray())
        data.add(header.toTypedArray())

        // Build merged rows
        val liveRows = readings.map { r ->
            val millis = r.timestamp.toDouble() * 1000
            val d = Date(millis.toLong())
            val dateStr = "${d.getDate()}/${d.getMonth()+1}/${d.getFullYear()}"
            val timeStr = "${d.getHours().toString().padStart(2,'0')}:${d.getMinutes().toString().padStart(2,'0')}"
            arrayOf<Any>(
                dateStr, timeStr,
                r.voltage, r.current, r.active_power, r.energy_consumption,
                r.power_factor, r.temperature, r.humidity, r.frequency,
                r.building_type, r.occupancy_level, r.operational_schedule,
                r.electricity_tariff, r.appliance_category, r.demand_kWh,
                "-", "-" , r.timestamp, "live"
            )
        }

        val forecastRows = forecast.map { rec ->
            val millis = rec.timestamp.toDouble() * 1000
            val d = Date(millis.toLong())
            val dateStr = "${d.getDate()}/${d.getMonth()+1}/${d.getFullYear()}"
            val timeStr = "${d.getHours().toString().padStart(2,'0')}:${d.getMinutes().toString().padStart(2,'0')}"
            arrayOf<Any>(
                dateStr, timeStr,
                "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-",
                rec.predXgb, rec.predRf,
                rec.timestamp.toLong(), "forecast"
            )
        }

        val merged = (liveRows + forecastRows).sortedWith(compareBy(
            { (it[it.size-2] as Number).toLong() }, // timestamp
            { it.last() as String }                 // type: live before forecast
        ))

        merged.forEach { row ->
            data.add(row.dropLast(2).toTypedArray()) // drop hidden timestamp + type
        }

        val sheet = XLSX.utils.aoa_to_sheet(data.toTypedArray())
        sheet["!merges"] = arrayOf(js("{ s:{r:0,c:0}, e:{r:0,c:16} }"))

        val cols = listOf(
            js("{ wch: 15 }"), js("{ wch: 10 }"), js("{ wch: 12 }"), js("{ wch: 12 }"),
            js("{ wch: 14 }"), js("{ wch: 18 }"), js("{ wch: 14 }"), js("{ wch: 12 }"),
            js("{ wch: 12 }"), js("{ wch: 14 }"), js("{ wch: 16 }"), js("{ wch: 16 }"),
            js("{ wch: 18 }"), js("{ wch: 16 }"), js("{ wch: 18 }"), js("{ wch: 14 }"),
            js("{ wch: 18 }"), js("{ wch: 18 }")
        )
        sheet["!cols"] = cols.toTypedArray()

        XLSX.utils.book_append_sheet(workbook, sheet, "Prediction Records")

        val writeOpts = js("""{ bookType: "xlsx", type: "array" }""")
        val wbout: dynamic = XLSX.write(workbook, writeOpts)
        val buffer: ArrayBuffer = wbout.unsafeCast<ArrayBuffer>()
        val uint8Array = Uint8Array(buffer)

        fileWriter(uint8Array.toByteArray())
    } catch (e: Throwable) {
        console.error("Failed to export prediction records", e)
    }
}


// Parameter whose values are comparable with the model forecasts
const val DEMAND_PARAM = "Demand (VA)"

@Composable
fun PlotGraph(
    data: List<liveData>,
    forecast: List<ForecastRecord>,
    interval: String,
    parameter: String,
    startTime: Double,   // ms, start of the viewed window
    endTime: Double      // ms, end of the viewed window
) {
    var canvasElement by remember { mutableStateOf<HTMLCanvasElement?>(null)}

    Div({
        style {
            width(100.percent)
            height(320.px)
            backgroundColor(Color.white)
            borderRadius(12.px)
        }
    }) {
        Canvas({
            attr("width","800")
            attr("height","400")
            ref {
                canvasElement = it
                onDispose { }
            }
            style {
                width(100.percent)
                height(100.percent)
            }
        })

        LaunchedEffect(data, forecast, canvasElement, parameter, startTime, endTime) {
            val canvas = canvasElement ?: return@LaunchedEffect
            val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
            val w = canvas.width.toDouble()
            val h = canvas.height.toDouble()
            ctx.clearRect(0.0,0.0,w,h)

            val left = 80.0
            val right = w - 40.0
            val top = 60.0
            val bottom = h - 60.0
            val plotHeight = bottom - top

            val showForecast = parameter == DEMAND_PARAM

            fun getValue(item: liveData): Double {
                return when(parameter) {
                    "Voltage" -> item.voltage.toDouble()
                    "Current" -> item.current.toDouble()
                    "Active Power" -> item.active_power.toDouble()
                    "Energy Consumption" -> item.energy_consumption.toDouble()
                    "Power Factor" -> item.power_factor.toDouble()
                    "Frequency" -> item.frequency.toDouble()
                    "Temperature" -> item.temperature.toDouble()
                    "Humidity" -> item.humidity.toDouble()
                    "Light Intensity" -> item.light_intensity.toDouble()
                    DEMAND_PARAM -> item.demand_kWh.toDouble()
                    else -> item.voltage.toDouble()
                }
            }

            // Forecast values only share a scale with demand; keep them out of other parameters' axis
            val values = data.map { getValue(it) } +
                    (if (showForecast) forecast.flatMap { listOf(it.predXgb, it.predRf) } else emptyList())

            ctx.font = "12px Arial"
            ctx.fillStyle = "#000000"
            ctx.fillText(parameter, 10.0, 20.0)

            // With no data the series are drawn as flat lines at 0
            var minValue = values.minOrNull() ?: 0.0
            var maxValue = values.maxOrNull() ?: 1.0

            if(kotlin.math.abs(maxValue - minValue) < 0.0001){
                minValue = if(minValue == 0.0) 0.0 else minValue - 1
                maxValue = maxValue + 1
            }

            val range = maxValue - minValue
            val padding = range * 0.15

            val yMin = if(minValue <= 0.0) 0.0 else minValue - padding
            val yMax = maxValue + padding
            val yRange = yMax - yMin

            // X axis spans the selected window, so forecasts sit where they belong in time
            val minTime = (startTime / 1000).toLong()
            val maxTime = (endTime / 1000).toLong()
            val timeRange = (maxTime - minTime).coerceAtLeast(1)

            fun x(time: Long): Double {
                return left + ((time - minTime).toDouble() / timeRange) * (right - left)
            }

            fun y(value: Double): Double {
                return bottom - ((value - yMin) / yRange) * plotHeight
            }

            // horizontal grid lines + labels
            for(i in 0..10){
                val py = bottom - plotHeight * i / 10
                ctx.strokeStyle = "#dddddd"
                ctx.lineWidth = 1.0
                ctx.beginPath()
                ctx.moveTo(left,py)
                ctx.lineTo(right,py)
                ctx.stroke()

                val label = yMin + ((yMax - yMin) * i / 10)
                ctx.fillStyle = "#000000"
                ctx.fillText(label.oneDecimal(), left - 55, py + 4)
            }

            // "Now" marker separating measured past from forecast future
            val nowSec = (Date.now() / 1000).toLong()
            if (nowSec in minTime..maxTime) {
                val px = x(nowSec)
                ctx.strokeStyle = "#999999"
                ctx.lineWidth = 1.0
                ctx.setLineDash(arrayOf(4.0, 4.0))
                ctx.beginPath()
                ctx.moveTo(px, top)
                ctx.lineTo(px, bottom)
                ctx.stroke()
                ctx.setLineDash(arrayOf())
                ctx.fillStyle = "#666666"
                ctx.fillText("now", px - 10, top - 6)
            }

            // Over gaps longer than this (seconds) the last value is held, instead of a long diagonal
            val maxGapSec = maxOf(1800L, timeRange / 100)

            // dashOffset lets two series with identical values interleave their dashes, so both colours show
            fun drawSeries(points: List<Pair<Long, Double>>, color: String, dashed: Boolean, dashOffset: Double = 0.0) {
                // Nothing in the window: a flat line at 0 across it
                val pts = points.sortedBy { it.first }.ifEmpty { listOf(minTime to 0.0, maxTime to 0.0) }
                ctx.strokeStyle = color
                ctx.lineWidth = 2.0
                ctx.setLineDash(if (dashed) arrayOf(6.0, 6.0) else arrayOf())
                ctx.lineDashOffset = dashOffset
                if (pts.size > 1) {
                    ctx.beginPath()
                    ctx.moveTo(x(pts[0].first), y(pts[0].second))
                    for (i in 1 until pts.size) {
                        val (prevT, prevV) = pts[i - 1]
                        val (t, v) = pts[i]
                        if (t - prevT > maxGapSec) ctx.lineTo(x(t), y(prevV))
                        ctx.lineTo(x(t), y(v))
                    }
                    ctx.stroke()
                }
                ctx.setLineDash(arrayOf())
                ctx.lineDashOffset = 0.0
                // Dots only when they can be told apart; a lone point is always drawn
                if (points.isNotEmpty() && points.size <= 200) {
                    ctx.fillStyle = color
                    points.forEach { (t, v) ->
                        ctx.beginPath()
                        ctx.arc(x(t), y(v), 3.0, 0.0, PI * 2)
                        ctx.fill()
                    }
                }
            }

            drawSeries(data.map { it.timestamp to getValue(it) }, "#FF0000", dashed = false)

            if (showForecast) {
                drawSeries(forecast.map { it.timestamp.toLong() to it.predXgb }, "#0000FF", dashed = true)
                drawSeries(forecast.map { it.timestamp.toLong() to it.predRf }, "#008000", dashed = true, dashOffset = 6.0)
            }

            // Legend at top right
            val legend = mutableListOf("Measured" to "#FF0000")
            if (showForecast) {
                legend += "XGBoost Forecast" to "#0000FF"
                legend += "RandomForest Forecast" to "#008000"
            }
            val legendX = w - 170
            legend.forEachIndexed { i, (label, color) ->
                val ly = 8.0 + i * 16
                ctx.fillStyle = color
                ctx.fillRect(legendX, ly, 12.0, 12.0)
                ctx.fillStyle = "#000000"
                ctx.fillText(label, legendX + 20, ly + 10)
            }

            // time axis labels
            for(i in 0..6){
                val px = left + ((right-left) * i / 6)
                val t = minTime + ((maxTime-minTime) * i / 6)
                val date = Date(t.toDouble() * 1000)
                val label = when(interval){
                    "Hourly", "Daily" -> "${date.getHours().toString().padStart(2,'0')}:${date.getMinutes().toString().padStart(2,'0')}"
                    "Monthly" -> date.getDate().toString().padStart(2,'0')
                    "Yearly" -> "${date.getDate().toString().padStart(2,'0')}-${(date.getMonth()+1).toString().padStart(2,'0')}"
                    else -> ""
                }
                ctx.fillStyle = "#000000"
                ctx.fillText(label,px-15,bottom+25)
            }

            ctx.fillStyle = "#000000"
            ctx.fillText("TIME",w/2,h-15)
        }
    }
}

@Composable
fun SettingsDivider() {
    Div({
        style {
            width(100.percent)
            height(1.px)
            minHeight(1.px)
            flexShrink(0)
            backgroundColor(Color("#C0C0C0"))
            marginTop(6.px)
            marginBottom(6.px)
        }
    }) {}
}


@Composable
fun SettingsPage(
    account: AccountEntity,
    accountUser: UserEntity,
    onSave: (AccountEntity, UserEntity) -> Unit,
    onCancel: () -> Unit
) {
    val buildingTypes = listOf("Residential", "Commercial", "Industrial")
    val schedules = listOf("Daytime", "Nighttime", "24/7")
    val tariffs = listOf("Peak", "Off-Peak", "Standard")

    val appliances = listOf("Lighting", "Entertainment", "HVAC", "Machinery")

    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null)}
    var feedbackMessage by remember { mutableStateOf("") } // optional if you show UI feedback

    var updatedAccount by remember { mutableStateOf(account) }
    var updatedUser by remember { mutableStateOf(accountUser) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showEnumDialog by remember { mutableStateOf(false) }
    var showNumberDialog by remember { mutableStateOf(false) }
    var editField by remember { mutableStateOf("") }
    var editValue by remember { mutableStateOf("") }
    var showGpsDialog by remember { mutableStateOf(false) }
   
    LaunchedEffect(account, accountUser) {
        updatedAccount = account
        updatedUser = accountUser
    }
    Div({
        style {
            width(100.percent)
            height(100.percent)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            backgroundColor(Color(lightAppBackground))
        }
    }) {
        HeaderSection(onCancel = onCancel)
        // ================= SETTINGS LIST =================
        Div({
            style {
                flexGrow(1)
                overflowY("auto")
                paddingLeft(if(isMobileLayout()) 2.px else 16.px)
                paddingRight(if(isMobileLayout()) 2.px else 16.px)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                gap(4.px)
            }
        }) {
            // NAME
            SettingsItem(
                field = "User Name",
                value = updatedUser.name,
                onEdit = {
                    editField = "name"
                    editValue = updatedUser.name
                    showEditDialog = true
                }
            )
            SettingsDivider()

            // ADDRESS
            SettingsItem(
                field = "Address",
                value = updatedAccount.address,
                onEdit = {
                    editField = "address"
                    editValue = updatedAccount.address
                    showEditDialog = true
                }
            )
            SettingsDivider()

            // LOCATION
            SettingsItem(
                field = "Location",
                value = if (updatedAccount.latitude.isNotEmpty() && updatedAccount.longitude.isNotEmpty()) {
                    "${updatedAccount.latitude}, ${updatedAccount.longitude}"
                } else {
                    "No location set"
                },
                onEdit = {
                    requestLocation { result ->
                        if (result.isOk) {
                            latitude = result.latitude
                            longitude = result.longitude
                            showGpsDialog = true
                        } else {
                            feedbackMessage = result.errorMessage ?: "Unknown location error"
                            showGpsDialog = false
                        }
                    }
                }
            )
            SettingsDivider()
            SettingsItem(
                field = "Data record interval (Secs)",
                value = updatedAccount.interval.toString(), // mapped to occupancy_level
                onEdit = {
                    editField = "Data record interval (Secs)"
                    editValue = updatedAccount.interval.toString()
                    showNumberDialog = true
                }
            )
            
            SettingsDivider()
            SettingsItem(
                field = "Occupancy Level",
                value = updatedAccount.future_Long.toString(), // mapped to occupancy_level
                onEdit = {
                    editField = "Occupancy Level"
                    editValue = updatedAccount.future_Long.toString()
                    showNumberDialog = true
                }
            )
            SettingsDivider()

            SettingsItem(
                field = "Building Type",
                value = updatedAccount.wifiName, // mapped to building_type
                onEdit = {
                    editField = "Building Type"
                    editValue = updatedAccount.wifiName
                    showEnumDialog = true
                }
            )
            SettingsDivider()
            
            SettingsItem(
                field = "Operational Schedule",
                value = updatedAccount.wifiPass, // mapped to operational_schedule
                onEdit = {
                    editField = "Operational Schedule"
                    editValue = updatedAccount.wifiPass
                    showEnumDialog = true
                }
            )
            SettingsDivider()

            SettingsItem(
                field = "Electricity Tariff",
                value = updatedAccount.activateDate, // mapped to electricity_tariff
                onEdit = {
                    editField = "Electricity Tariff"
                    editValue = updatedAccount.activateDate
                    showEnumDialog = true
                }
            )
            SettingsDivider()

            SettingsItem(
                field = "Appliance Category",
                value = updatedAccount.lastDate, // mapped to appliance_category
                onEdit = {
                    editField = "Appliance Category"
                    editValue = updatedAccount.lastDate
                    showEnumDialog = true
                }
            )
        }

        Div({ style { height(12.px) } })
        val accountChanged = updatedAccount != account
        val userChanged = updatedUser != accountUser
        if (accountChanged || userChanged) {
            SaveButton {
                onSave(updatedAccount, updatedUser)
            }
        }
        Div({ style { height(12.px) } })
    }
    if (showEnumDialog) {
        val options = when (editField) {
            "Building Type" -> buildingTypes
            "Operational Schedule" -> schedules
            "Electricity Tariff" -> tariffs
            "Appliance Category" -> appliances
            else -> emptyList()
        }

        EnumSelectionDialog(
            field = editField,
            options = options,
            currentValue = editValue,
            onDismiss = { showEnumDialog = false },
            onConfirm = { value ->
                when (editField) {
                    "Building Type" -> updatedAccount = updatedAccount.copy(wifiName = value)
                    "Operational Schedule" -> updatedAccount = updatedAccount.copy(wifiPass = value)
                    "Electricity Tariff" -> updatedAccount = updatedAccount.copy(activateDate = value)
                    "Appliance Category" -> updatedAccount = updatedAccount.copy(lastDate = value)
                }
                showEnumDialog = false
            }
        )
    }
    
    // ================= EDIT DIALOG =================
    if (showEditDialog) {
        EditDialog(
            field = editField,
            value = editValue,
            onDismiss = { showEditDialog = false },
            onConfirm = { value ->
                when (editField) {
                    "name" -> updatedUser = updatedUser.copy(name = value)
                    "address" -> updatedAccount = updatedAccount.copy(address = value)
                }
                showEditDialog = false
            }
        )
    }

    // ================= EDIT DIALOG =================
    if (showEditDialog) {
        EditDialog(
            field = editField,
            value = editValue,
            onDismiss = { showEditDialog = false },
            onConfirm = { value ->
                when (editField) {
                    "wifi_password" -> updatedAccount = updatedAccount.copy(wifiPass = value)
                }
                showEditDialog = false
            }
        )
    }

    if(showNumberDialog){
        NumberEditDialog(
            field = editField,
            value = editValue,
            onDismiss = { showNumberDialog = false },
            onConfirm = { value ->
                when (editField) {
                    "Data record interval (Secs)" -> updatedAccount = updatedAccount.copy(interval = value.toLong())
                    "Occupancy Level" -> updatedAccount = updatedAccount.copy(future_Long = value.toLong())
                }
                showNumberDialog = false
            }
        )
    }
    // ================= GPS DIALOG (NEW SIMPLE INLINE) =================
    if (showGpsDialog && latitude != null && longitude != null) {
        Div({
            style {
                position(Position.Fixed)
                top(0.px)
                left(0.px)
                width(100.percent)
                height(100.percent)
                backgroundColor(Color("rgba(0,0,0,0.3)"))
                display(DisplayStyle.Flex)
                justifyContent(JustifyContent.Center)
                alignItems(AlignItems.Center)
            }
            onClick { showGpsDialog = false }
        }) {
            Div({
                onClick { it.stopPropagation() }
                style {
                    backgroundColor(Color(AppFrontGround))
                    padding(16.px)
                    borderRadius(10.px)
                    display(DisplayStyle.Flex)
                    flexDirection(FlexDirection.Column)
                    gap(12.px)
                    minWidth(320.px)
                }
            }) {
                H3({ style { textAlign("center") } }) {
                    Text("Confirm GPS Location")
                }
                Iframe({
                    attr(
                        "src",
                        "https://maps.google.com/maps?q=$latitude,$longitude&z=15&output=embed"
                    )
                    style {
                        width(100.percent)
                        height(300.px)
                        border(0.px)
                        borderRadius(10.px)
                    }
                })

                Button(attrs = {
                    onClick {
                        updatedAccount = updatedAccount.copy(
                            latitude = latitude.toString(),
                            longitude = longitude.toString()
                        )
                        showGpsDialog = false
                    }
                }) {
                    Text("Accept")
                }
                Button(attrs = {
                    onClick { showGpsDialog = false }
                }) {
                    Text("Cancel")
                }
            }
        }
    }
}


@Composable
fun EnumSelectionDialog(
    field: String,
    options: List<String>,
    currentValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var selectedValue by remember { mutableStateOf(currentValue) }

    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.percent)
            backgroundColor(Color("rgba(0,0,0,0.3)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
            property("z-index", "9999")
        }
        onClick { onDismiss() }
    }) {
        Div({
            onClick { it.stopPropagation() }
            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                gap(10.px)
                minWidth(260.px)
                maxWidth(320.px)
                backgroundColor(Color(AppFrontGround))
                color(Color(TextColor))
                borderRadius(10.px)
                padding(16.px)
                boxSizing("border-box")
            }
        }) {
            H3({
                style {
                    textAlign("center")
                    fontSize(16.px)
                    margin(0.px)
                    color(Color(actionColor))
                }
            }) { Text("Select $field") }

            options.forEach { option ->
                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Row)
                        alignItems(AlignItems.Center)
                        justifyContent(JustifyContent.SpaceBetween)
                        padding(10.px)
                        cursor("pointer")
                        borderRadius(6.px)
                    }
                    onClick { selectedValue = option }
                }) {
                    Span({ style { color(Color(TextColor)) } }) { Text(option) }
                    if (selectedValue == option) {
                        Div({ style { width(18.px); height(18.px) } }) {
                            IconForPanel("check")
                        }
                    }
                }
            }

            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    marginTop(8.px)
                }
            }) {
                Button(attrs = {
                    onClick { onDismiss() }
                    style {
                        padding(6.px, 12.px)
                        backgroundColor(Color(actionColor))
                        fontSize(13.px)
                        color(Color("white"))
                        cursor("pointer")
                    }
                }) { Text("Cancel") }

                Button(attrs = {
                    onClick { onConfirm(selectedValue) }
                    style {
                        padding(6.px, 12.px)
                        fontSize(13.px)
                        backgroundColor(Color(actionColor))
                        color(Color("white"))
                        borderRadius(6.px)
                        cursor("pointer")
                    }
                }) { Text("Confirm") }
            }
        }
    }
}


@Composable
fun SaveButton(onClick: () -> Unit) {
    Div({
        style {
            width(100.percent)
            marginTop(4.px)       // area around the button
            marginBottom((-4).px) // shift upwards slightly
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
        }
    }) {
        Button(attrs = {
            onClick { onClick() }
            style {
                padding(8.px, 16.px)
                backgroundColor(Color(actionColor))   // your action color
                color(Color("white"))          // your foreground text color
                borderRadius(6.px)
                cursor("pointer")
            }
        }) {
            Text(I18n.t("save"))
        }
    }
}

@Composable
fun SettingsItem(
    field: String,
    value: String,
    onEdit: () -> Unit,
    showEditIcon: Boolean = true
) {
    Div({
        style {
            width(100.percent)
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.SpaceBetween)
            alignItems(AlignItems.Center)
            paddingTop(1.px)
            paddingBottom(1.px)
            paddingRight(16.px)
            boxSizing("border-box")
        }
    }) {
        // Field + Value
        Div({
            style {
                flexGrow(1)
                paddingLeft(16.px)
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                boxSizing("border-box")
            }
        }) {
            // Field label
            Span({
                style {
                    fontSize(16.px)
                    color(Color(TextColor))
                }
            }) { Text(field) }

            // Value or masked password
            if (
                field == I18n.t("password") ||
                field == I18n.t("bluetooth_password") ||
                field == I18n.t("wifi_password")
            ) {
                Span({
                    style {
                        fontSize(14.px)
                        fontStyle("italic")
                        color(Color("gray"))
                    }
                }) { Text("********") }
            } else {
                Span({
                    style {
                        fontSize(14.px)
                        fontStyle("italic")
                        color(Color("gray"))
                    }
                }) { Text(value) }
            }
        }

        // Edit icon
        if (showEditIcon) {
            Button(attrs = {
                onClick { onEdit() }
                style {
                    backgroundColor(Color("transparent"))
                    border(width = 0.px)
                    cursor("pointer")
                }
            }) {
                IconForPanel("edit", altText = "edit", tint = "#FFFFFF")
            }
        }
    }
}


@Composable
fun LanguageSelectionDialog(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val languages = listOf("English", "Français", "العربية")
    var selectedLanguage by remember { mutableStateOf(currentLanguage) }

    // FULLSCREEN OVERLAY
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.percent)

            backgroundColor(Color("rgba(0, 0, 0, 0.3)"))

            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)

            property("z-index", "9999")
        }

        onClick { onDismiss() }
    }) {

        // DIALOG CARD
        Div({
            onClick { it.stopPropagation() }

            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                gap(10.px)

                property("width", "auto")
                minWidth(260.px)
                maxWidth(320.px)

                backgroundColor(Color(AppFrontGround))
                color(Color(TextColor))
                borderRadius(10.px)
                padding(16.px)
                boxSizing("border-box")
            }
        }) {

            // TITLE
            H3({
                style {
                    textAlign("center")
                    fontSize(16.px)
                    margin(0.px)
                    color(Color(actionColor))
                }
            }) {
                Text(I18n.t("select_language"))
            }

            // LANGUAGE LIST
            languages.forEach { language ->

                Div({
                    style {
                        display(DisplayStyle.Flex)
                        flexDirection(FlexDirection.Row)
                        alignItems(AlignItems.Center)
                        justifyContent(JustifyContent.SpaceBetween)

                        padding(10.px)
                        cursor("pointer")
                        borderRadius(6.px)
                    }

                    onClick {
                        selectedLanguage = language
                    }
                }) {

                    Span({
                        style {
                            color(Color(TextColor))
                        }
                    }) {
                        Text(language)
                    }

                    if (selectedLanguage == language) {
                        Div({
                            style {
                                width(18.px)
                                height(18.px)
                                flexShrink(0)
                            }
                        }) {
                            IconForPanel("check")
                        }
                    }
                }
            }

            // BUTTONS
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    marginTop(8.px)
                }
            }) {

                Button(attrs = {
                    onClick { onDismiss() }
                    style {
                        padding(6.px, 12.px)
                        fontSize(13.px)
                        cursor("pointer")
                    }
                }) {
                    Text(I18n.t("cancel"))
                }

                Button(attrs = {
                    onClick { onConfirm(selectedLanguage) }
                    style {
                        padding(6.px, 12.px)
                        fontSize(13.px)

                        backgroundColor(Color(actionColor))
                        color(Color(AppFrontGround))

                        borderRadius(6.px)
                        cursor("pointer")
                    }
                }) {
                    Text(I18n.t("confirm"))
                }
            }
        }
    }
}

@Composable
fun EditDialog(
    field: String,
    value: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(value) }

    // FULLSCREEN OVERLAY
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.percent)

            backgroundColor(Color("rgba(0, 0, 0, 0.3)"))

            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)

            property("z-index", "9999")
        }

        onClick { onDismiss() }
    }) {

        // DIALOG CARD
        Div({
            onClick { it.stopPropagation() }

            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                gap(10.px)

                property("width", "auto")
                minWidth(260.px)
                maxWidth(320.px)

                backgroundColor(Color(AppFrontGround))
                color(Color(TextColor))
                borderRadius(10.px)
                padding(16.px)
                boxSizing("border-box")
            }
        }) {

            // TITLE
            H3({
                style {
                    textAlign("center")
                    fontSize(16.px)
                    margin(0.px)
                    color(Color(actionColor))
                }
            }) {
                Text(I18n.t("edit_field", field))
            }

            // INPUT
            Input(InputType.Text) {
                value(text)
                placeholder(I18n.t("enter_new", field))

                onInput { ev ->
                    text = ev.value.filter { it != '[' && it != '#' }
                }

                style {
                    width(100.percent)
                    padding(7.px)
                    fontSize(14.px)

                    color(Color(TextColor))
                    backgroundColor(Color(AppFrontGround))

                    border {
                        style(LineStyle.Solid)
                        color(Color(theIconColor))
                        width(1.px)
                    }

                    borderRadius(6.px)
                    boxSizing("border-box")
                }
            }

            // BUTTONS
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    marginTop(8.px)
                }
            }) {

                Button(attrs = {
                    onClick { onDismiss() }
                    style {
                        padding(6.px, 12.px)
                        fontSize(13.px)
                        cursor("pointer")
                    }
                }) {
                    Text(I18n.t("close"))
                }

                Button(attrs = {
                    onClick { onConfirm(text) }
                    style {
                        padding(6.px, 12.px)
                        fontSize(13.px)

                        backgroundColor(Color(actionColor))
                        color(Color(AppFrontGround))

                        borderRadius(6.px)
                        cursor("pointer")
                    }
                }) {
                    Text(I18n.t("ok"))
                }
            }
        }
    }
}

@Composable
fun NumberEditDialog(
    field: String,
    value: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(value) }

    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.percent)

            backgroundColor(Color("rgba(0, 0, 0, 0.3)"))

            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)

            property("z-index", "9999")
        }

        onClick { onDismiss() }
    }) {

        Div({
            onClick { it.stopPropagation() }

            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                gap(10.px)

                minWidth(260.px)
                maxWidth(320.px)

                backgroundColor(Color(AppFrontGround))
                color(Color(TextColor))

                borderRadius(10.px)
                padding(16.px)

                boxSizing("border-box")
            }
        }) {

            H3({
                style {
                    textAlign("center")
                    fontSize(16.px)
                    margin(0.px)
                    color(Color("white"))
                }
            }) {
                Text(I18n.t("edit_field", field))
            }

            Input(InputType.Number) {
                value(text)
                placeholder(I18n.t("enter_new", field))
                attr("step", "0.01")
                attr("min", "0")
                onInput { ev ->
                    text = ev.value.toString()
                }

                style {
                    width(100.percent)
                    padding(7.px)
                    fontSize(14.px)

                    color(Color(TextColor))
                    backgroundColor(Color(AppFrontGround))

                    border {
                        style(LineStyle.Solid)
                        color(Color(theIconColor))
                        width(1.px)
                    }

                    borderRadius(6.px)
                    boxSizing("border-box")
                }
            }


            Div({

                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceBetween)
                    marginTop(8.px)
                }

            }) {
                Button(attrs = {
                    onClick { onDismiss() }
                    style {
                        padding(6.px, 12.px)
                        fontSize(13.px)
                        cursor("pointer")
                    }

                }) {
                    Text(I18n.t("close"))
                }



                Button(attrs = {

                    onClick {

                        // Prevent empty decimal submissions
                        if (text.isNotEmpty() && text != ".") {
                            onConfirm(text)
                        }
                    }
                    style {
                        padding(6.px, 12.px)
                        fontSize(13.px)
                        backgroundColor(Color(actionColor))
                        color(Color("white"))
                        borderRadius(6.px)
                        cursor("pointer")
                    }
                }) {
                    Text(I18n.t("ok"))
                }
            }
        }
    }
}

//end



@Composable
fun BottomNavItemView(
    item: SidebarItem,
    currentPage: String,
    onSelect: (String) -> Unit
) {
    val isSelected = currentPage == item.key

    Div(attrs = {
        style {
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Column)
            alignItems(AlignItems.Center)
            justifyContent(JustifyContent.Center)

            cursor("pointer")
            boxSizing("border-box")

            width(80.px)
            height(60.px)

            property("transition", "all 0.2s ease")

            if (isSelected) {
                // Use your darker blue-black instead of pure white
                backgroundColor(Color("#0C0C20"))
                color(Color("#FFFFFF")) // pure white text/icons
                fontWeight("bold")

                property("border-radius", "12px 12px 0 0") // softer rounding
                property("margin-top", "-6px")             // subtle lift
                property(
                    "box-shadow",
                    "0px -2px 6px rgba(0,0,0,0.25)"       // blends better
                )
            } else {
                backgroundColor(Color.transparent)
                color(Color("#D9D9D9")) // soft gray-white when not selected
                fontWeight("normal")
            }
        }

        onClick { onSelect(item.key) }
    }) {
        val iconTint = if (isSelected) "#FFFFFF" else "#D9D9D9"

        IconForPanel(
            item.icon,
            24,
            altText = "${item.key} icon",
            tint = iconTint
        )
        Text(item.key)
    }
}


// Sidebar item model
data class SidebarItem(
    val key: String,
    val icon: String
)

@Composable
fun SidebarItemView(
    item: SidebarItem,
    currentPage: String,
    onSelect: (String) -> Unit
) {
    val isSelected = currentPage == item.key
    Div(attrs = {
        style {
            width(100.percent)
            height(100.percent)
            display(DisplayStyle.Flex)
            flexDirection(FlexDirection.Row)
            alignItems(AlignItems.Center)
            justifyContent(JustifyContent.FlexStart)
            gap(10.px)
            fontFamily("sans-serif")
            fontSize(20.px)
            boxSizing("border-box")
            cursor("pointer")
            paddingLeft(12.px)

            if (isSelected) {
                backgroundColor(Color(lightAppBackground))
                color(Color("#FFFFFF")) // pure white text
                fontWeight("bold")      // bold when selected
                property("border-radius", "20px 0 0 20px")
            } else {
                backgroundColor(Color(barColor))
                color(Color("#D9D9D9")) // soft gray-white when not selected
                fontWeight("normal")
            }
        }
        onClick { onSelect(item.key) }
    }) {
        val iconTint = if (isSelected) "#FFFFFF" else "#D9D9D9"
        IconForPanel(item.icon, 32, altText = "${item.key} icon", tint = iconTint)
        Text(item.key)
    }
}



fun getCurrentDateFormatted(locale: String = "en-US"): String {
    val now = js("new Date()") as Date
    val year = now.getFullYear()
    val month = (now.getMonth() + 1)
    val day = now.getDate()

    fun toLocaleDigits(number: Int): String {
        return number.asDynamic().toLocaleString(locale).unsafeCast<String>().padStart(2, '0')
    }

    return "${toLocaleDigits(year)}-${toLocaleDigits(month)}-${toLocaleDigits(day)}"
}


@Composable
fun HeaderSection(
    onCancel: () -> Unit,
    isSettings: Boolean = true
) {
    // Outer row
    Div({
        style {
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Start)
            alignItems(AlignItems.Center)
            width(100.percent)
            paddingLeft(8.px)
            paddingRight(8.px)
            paddingTop(1.px)
            paddingBottom(1.px)
            boxSizing("border-box")
        }
    }) {
        // Clickable row
        Div({
            style {
                display(DisplayStyle.Flex)
                alignItems(AlignItems.Center)
                cursor("pointer")
            }
            onClick { onCancel() }
        }) {
            // Back icon box
            Div({
                style {
                    width(25.px)
                    height(25.px)
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.Center)
                    alignItems(AlignItems.Center)
                }
            }) {
                IconForPanel("back", 24, altText = "back", tint = "#FFFFFF")
            }
            // Title text
            /*Span({
                style {
                    marginLeft(8.px)
                    fontSize(20.px)
                    fontWeight("bold")
                    color(Color(MainHeadingColor))
                    textAlign("start")
                }
            }) {
                Text(if (isSettings) I18n.t("settings") else I18n.t("settings"))
            }*/
        }
    }
}


@Composable
fun ShowWifiSelectorDialog(
    wifiNames: List<String>,
    oldWifiName: String,
    isScanning: Boolean,
    showManualEntry: Boolean,
    onWifiSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var manualText by remember { mutableStateOf(oldWifiName) }
    // FULLSCREEN OVERLAY (like EditDialog)
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.percent)
            backgroundColor(Color("rgba(0, 0, 0, 0.3)"))
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)

            property("z-index", "9999")
        }

        onClick { onDismiss() }
    }) {

        // DIALOG CARD
        Div({
            onClick { it.stopPropagation() }

            style {
                display(DisplayStyle.Flex)
                flexDirection(FlexDirection.Column)
                gap(10.px)
                property("width", "auto")
                minWidth(260.px)
                maxWidth(500.px)
                backgroundColor(Color(AppFrontGround))
                color(Color(TextColor))
                borderRadius(10.px)
                padding(16.px)
                boxSizing("border-box")
            }
        }) {

            // TITLE
            H3({
                style {
                    textAlign("center")
                    fontSize(16.px)
                    margin(0.px)
                    color(Color(actionColor))
                }
            }) {
                Text(I18n.t("wifi_selector_title"))
            }

            // ================= MANUAL MODE =================
            if (showManualEntry) {

                Input(InputType.Text) {
                    value(manualText)
                    placeholder(I18n.t("wifi_name"))

                    onInput { ev ->
                        manualText = ev.value
                    }

                    style {
                        width(90.percent)
                        padding(8.px)
                    }
                }

                Div({
                    style {
                        display(DisplayStyle.Flex)
                        justifyContent(JustifyContent.SpaceBetween)
                        marginTop(10.px)
                    }
                }) {

                    Button(attrs = {
                        onClick { onDismiss() }
                    }) {
                        Text(I18n.t("close"))
                    }

                    Button(attrs = {
                        onClick {
                            onWifiSelected(manualText)
                        }
                        style {
                            backgroundColor(Color(actionColor))
                            color(Color("white"))
                            padding(6.px, 12.px)
                            borderRadius(6.px)
                        }
                    }) {
                        Text(I18n.t("ok"))
                    }
                }
            }
            // ================= SCAN MODE =================
            else {
                when {
                    isScanning -> {
                        P({ style { textAlign("center") } }) {
                            Text(I18n.t("scanning..."))
                        }
                    }
                    wifiNames.isEmpty() -> {
                        P({
                            style {
                                textAlign("center")
                                color(Color("#808080"))
                            }
                        }) {
                            Text(I18n.t("no_wifi_found"))
                        }
                    }

                    else -> {
                        Div({
                            style {
                                maxHeight(300.px)
                                overflowY("auto")
                            }
                        }) {

                            wifiNames.forEach { ssid ->

                                Button(attrs = {
                                    onClick {
                                        onWifiSelected(ssid)
                                    }
                                    style {
                                        width(90.percent)
                                        marginTop(4.px)
                                        padding(8.px)
                                        backgroundColor(Color(AppFrontGround))
                                        color(Color(TextColor))
                                        borderRadius(6.px)
                                    }
                                }) {
                                    Text(ssid)
                                }
                            }
                        }
                    }
                }
                // CLOSE BUTTON
                Button(attrs = {
                    onClick { onDismiss() }
                    style {
                        marginTop(12.px)
                        padding(8.px, 16.px)
                    }
                }) {
                    Text(I18n.t("close"))
                }
            }
        }
    }
}

@Composable
fun LogoutDialog(
    onConfirmLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    // Dialog container
    Div({
        style {
            position(Position.Fixed)
            top(0.px)
            left(0.px)
            width(100.percent)
            height(100.percent)
            backgroundColor(Color("rgba(0,0,0,0.5)")) // ✅ semi‑transparent black
            display(DisplayStyle.Flex)
            justifyContent(JustifyContent.Center)
            alignItems(AlignItems.Center)
        }
        onClick { onDismiss() } // click outside to dismiss
    }) {
        // Dialog box
        Div({
            style {
                backgroundColor(Color(AppFrontGround))
                borderRadius(16.px)
                padding(16.px)
                width(80.percent)
                maxWidth(400.px)
            }
            onClick { /* stop propagation so inner clicks don't dismiss */ }
        }) {
            // Title
            H3({
                style {
                    textAlign("center")
                    color(Color(actionColor))
                    fontSize(18.px)
                    fontWeight("bold")
                }
            }) { Text(I18n.t("logout")) }

            // Message
            P({
                style {
                    textAlign("center")
                    color(Color(TextColor))
                    fontSize(16.px)
                    marginTop(12.px)
                    marginBottom(16.px)
                }
            }) { Text(I18n.t("logout_confirmation")) }

            // Buttons row
            Div({
                style {
                    display(DisplayStyle.Flex)
                    justifyContent(JustifyContent.SpaceEvenly)
                    marginTop(16.px)
                }
            }) {
                Button(attrs = {
                    style {
                        backgroundColor(Color(actionColor))
                        color(Color(AppFrontGround))
                        padding(8.px, 16.px)
                        borderRadius(8.px)
                        border { style(LineStyle.None) }
                        cursor("pointer")
                    }
                    onClick { onDismiss() }
                }) { Text(I18n.t("no")) }

                Button(attrs = {
                    style {
                        backgroundColor(Color(actionColor))
                        color(Color(AppFrontGround))
                        padding(8.px, 16.px)
                        borderRadius(8.px)
                        border { style(LineStyle.None) }
                        cursor("pointer")
                    }
                    onClick { onConfirmLogout() }
                }) { Text(I18n.t("yes")) }
            }
        }
    }
}