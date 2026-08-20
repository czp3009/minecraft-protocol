@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.account.auth.*
import com.hiczp.minecraft.protocol.auth.MinecraftIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOnlineIdentity
import com.kgit2.kommand.Platform
import com.kgit2.kommand.platform
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.charsets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal fun interface BrowserService {
    suspend fun open(url: String)
}

internal enum class MicrosoftLoginStage {
    STARTING_CALLBACK,
    WAITING_FOR_BROWSER,
    VERIFYING_ACCOUNT,
    COMPLETE,
}

internal object SystemBrowserService : BrowserService {
    override suspend fun open(url: String) = withContext(Dispatchers.Default) {
        val command = when (platform) {
            Platform.MINGW_X64 -> Command("rundll32.exe").args("url.dll,FileProtocolHandler", url)
            Platform.LINUX_X64, Platform.LINUX_ARM64 -> Command("xdg-open").arg(url)
            Platform.MACOS_X64, Platform.MACOS_ARM64 -> Command("/usr/bin/open").arg(url)
        }
        val status = command
            .stdout(Stdio.Null)
            .stderr(Stdio.Null)
            .status()
        require(status == 0) { "Failed to open the system browser (exit $status)" }
    }
}

internal class AccountService(
    private val httpClient: HttpClient,
    private val store: LauncherStore,
    private val browserService: BrowserService = SystemBrowserService,
) {
    private val refreshStateMutex = Mutex()
    private val refreshLocks = mutableMapOf<Uuid, Mutex>()
    private val failedRefreshes = mutableSetOf<Uuid>()

    suspend fun addOffline(name: String): StoredAccount {
        val validatedName = name.trim()
        val identity = MinecraftOfflineIdentity(validatedName)
        val account = StoredAccount(identity = identity)
        store.auth.update {
            selectedIdentityId = identity.id
            accounts = accounts.filterNot { it.identity.id == identity.id } + account
        }
        clearRefreshFailure(identity.id)
        return account
    }

    suspend fun updateOffline(identityId: Uuid, name: String): StoredAccount {
        val replacement = MinecraftOfflineIdentity(name.trim())
        return requireNotNull(
            updateAccountIfPresent(identityId) { account ->
                require(account.identity is MinecraftOfflineIdentity) { "Only offline identities can be renamed" }
                account.copy(identity = replacement)
            },
        ) { "Account does not exist" }
    }

    suspend fun select(identityId: Uuid) {
        store.auth.update {
            require(accounts.any { it.identity.id == identityId }) { "Account does not exist" }
            selectedIdentityId = identityId
        }
    }

    suspend fun delete(identityId: Uuid) {
        store.auth.update {
            accounts = accounts.filterNot { it.identity.id == identityId }
            if (selectedIdentityId == identityId) selectedIdentityId = null
        }
        clearRefreshFailure(identityId)
    }

    suspend fun loginMicrosoft(
        replacingIdentityId: Uuid? = null,
        onProgress: (MicrosoftLoginStage) -> Unit = {},
    ): StoredAccount {
        onProgress(MicrosoftLoginStage.STARTING_CALLBACK)
        val microsoftToken = receiveAuthorizationCode(onProgress)
        onProgress(MicrosoftLoginStage.VERIFYING_ACCOUNT)
        val result = completeMinecraftLogin(microsoftToken)
        val refreshToken = requireNotNull(microsoftToken.refreshToken) {
            "Microsoft did not return a refresh token; check the public client and offline_access configuration"
        }
        val account = StoredAccount(
            identity = result.identity,
            microsoftRefreshToken = refreshToken,
            minecraftAccessTokenExpiresAtEpochSeconds = Clock.System.now().epochSeconds + result.expiresIn,
        )
        val lockIdentityId = replacingIdentityId ?: account.identity.id
        refreshLock(lockIdentityId).withLock {
            store.auth.update {
                if (replacingIdentityId == null) {
                    selectedIdentityId = account.identity.id
                    accounts = accounts.filterNot { it.identity.id == account.identity.id } + account
                } else {
                    val index = accounts.indexOfFirst { it.identity.id == replacingIdentityId }
                    require(index >= 0) { "Account does not exist" }
                    require(accounts[index].identity is MinecraftOnlineIdentity) {
                        "Only Microsoft accounts can be signed in again"
                    }
                    require(account.identity.id == replacingIdentityId) {
                        "The signed-in Minecraft profile does not match the account being updated"
                    }
                    accounts = accounts.toMutableList().apply { this[index] = account }
                }
            }
            clearRefreshFailure(lockIdentityId)
        }
        onProgress(MicrosoftLoginStage.COMPLETE)
        return account
    }

    fun selectedAccount(): StoredAccount? = store.auth.read {
        accounts.singleOrNull { it.identity.id == selectedIdentityId }
    }

    fun selectedIdentity(): MinecraftIdentity =
        selectedAccount()?.identity ?: MinecraftOfflineIdentity(DEFAULT_OFFLINE_PLAYER_NAME)

    fun needsRefresh(account: StoredAccount, nowEpochSeconds: Long = Clock.System.now().epochSeconds): Boolean {
        if (account.identity !is MinecraftOnlineIdentity) return false
        val expiresAt = account.minecraftAccessTokenExpiresAtEpochSeconds ?: return true
        return expiresAt <= nowEpochSeconds + TOKEN_REFRESH_SAFETY_SECONDS
    }

    suspend fun refreshIfNeeded(identityId: Uuid): StoredAccount? = refreshLock(identityId).withLock {
        val account = store.auth.read { accounts.singleOrNull { it.identity.id == identityId } }
            ?: return@withLock null
        val identity = account.identity as? MinecraftOnlineIdentity ?: return@withLock account
        if (!needsRefresh(account)) {
            clearRefreshFailure(identityId)
            return@withLock account
        }
        if (hasRefreshFailure(identityId)) throw AccountLoginExpiredException(identity.name)

        try {
            val refreshed = refreshOnlineAccount(account, identity)
            clearRefreshFailure(identityId)
            refreshed
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (store.auth.read { accounts.none { it.identity.id == identityId } }) {
                clearRefreshFailure(identityId)
                return@withLock null
            }
            markRefreshFailure(identityId)
            throw AccountLoginExpiredException(identity.name, failure)
        }
    }

    private suspend fun refreshOnlineAccount(
        account: StoredAccount,
        identity: MinecraftOnlineIdentity,
    ): StoredAccount? {
        val refreshToken = requireNotNull(account.microsoftRefreshToken)
        val microsoftToken = MicrosoftOAuthApi(httpClient).tokenWithRefreshToken(
            MicrosoftOAuthTools.refreshTokenRequest(MICROSOFT_CLIENT_ID, refreshToken),
        )
        val rotatedRefreshToken = microsoftToken.refreshToken ?: refreshToken
        if (rotatedRefreshToken != refreshToken) {
            updateAccountIfPresent(identity.id) { it.copy(microsoftRefreshToken = rotatedRefreshToken) }
                ?: return null
        }
        val login = completeMinecraftLogin(microsoftToken)
        require(login.identity.id == identity.id) {
            "The refreshed Minecraft profile does not match the stored account"
        }
        return updateAccountIfPresent(identity.id) {
            it.copy(
                identity = login.identity,
                microsoftRefreshToken = rotatedRefreshToken,
                minecraftAccessTokenExpiresAtEpochSeconds = Clock.System.now().epochSeconds + login.expiresIn,
            )
        }
    }

    private suspend fun updateAccountIfPresent(
        identityId: Uuid,
        transform: (StoredAccount) -> StoredAccount,
    ): StoredAccount? {
        var updated: StoredAccount? = null
        store.auth.update {
            val index = accounts.indexOfFirst { it.identity.id == identityId }
            if (index < 0) return@update
            val replacement = transform(accounts[index])
            updated = replacement
            if (selectedIdentityId == identityId) selectedIdentityId = replacement.identity.id
            accounts = accounts.toMutableList().apply { this[index] = replacement }
        }
        return updated
    }

    private suspend fun refreshLock(identityId: Uuid): Mutex = refreshStateMutex.withLock {
        refreshLocks.getOrPut(identityId) { Mutex() }
    }

    private suspend fun hasRefreshFailure(identityId: Uuid): Boolean = refreshStateMutex.withLock {
        identityId in failedRefreshes
    }

    private suspend fun markRefreshFailure(identityId: Uuid) = refreshStateMutex.withLock {
        failedRefreshes += identityId
    }

    private suspend fun clearRefreshFailure(identityId: Uuid) = refreshStateMutex.withLock {
        failedRefreshes -= identityId
    }

    private suspend fun completeMinecraftLogin(microsoftToken: MicrosoftTokenResponse): MinecraftLoginResult {
        val xboxApi = XboxAuthenticationApi(httpClient)
        val userToken = xboxApi.authenticateUser(XboxAuthenticationTools.userAuthenticationRequest(microsoftToken))
        val xstsToken = xboxApi.authorizeXsts(XboxAuthenticationTools.xstsAuthorizationRequest(userToken))
        val minecraftApi = MinecraftServicesApi(httpClient)
        val login = minecraftApi.loginWithXbox(MinecraftServicesTools.xboxLoginRequest(xstsToken))
        val entitlements = minecraftApi.getStoreEntitlements(login.accessToken)
        require(MinecraftServicesTools.hasJavaEditionEntitlement(entitlements)) {
            "This Microsoft account does not own Minecraft: Java Edition"
        }
        val profile = minecraftApi.getMinecraftProfile(login.accessToken)
        val identity = MinecraftOnlineIdentity(Uuid.parse(profile.id), profile.name, login.accessToken)
        return MinecraftLoginResult(identity, login.expiresIn)
    }

    private suspend fun receiveAuthorizationCode(onProgress: (MicrosoftLoginStage) -> Unit): MicrosoftTokenResponse {
        val state = MicrosoftOAuthTools.generateState()
        val verifier = MicrosoftOAuthTools.generateCodeVerifier()
        val terminal = CompletableDeferred<MicrosoftTokenResponse>()
        val terminalOwner = Mutex()
        var redirectUri = ""
        val server = embeddedServer(CIO, host = OAUTH_REDIRECT_HOST, port = 0) {
            routing {
                get(OAUTH_REDIRECT_PATH) {
                    val states = call.request.queryParameters.getAll("state")
                    if (states?.singleOrNull() != state) {
                        call.respondText(
                            "Invalid authorization state. Return to the launcher and try again.",
                            ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                            HttpStatusCode.BadRequest,
                        )
                        return@get
                    }
                    val codes = call.request.queryParameters.getAll("code")
                    val errors = call.request.queryParameters.getAll("error")
                    if ((codes?.size ?: 0) + (errors?.size ?: 0) != 1 || !terminalOwner.tryLock()) {
                        call.respondText(
                            "This authorization callback is invalid or has already been handled.",
                            ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                            HttpStatusCode.Conflict,
                        )
                        return@get
                    }
                    val oauthError = errors?.singleOrNull()
                    if (oauthError != null) {
                        call.respondText(
                            "Microsoft authorization was not completed. Return to the launcher and try again.",
                            ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                            HttpStatusCode.BadRequest,
                        )
                        terminal.completeExceptionally(IllegalStateException("Microsoft authorization was denied"))
                        return@get
                    }
                    try {
                        val request = MicrosoftOAuthTools.authorizationCodeTokenRequest(
                            MICROSOFT_CLIENT_ID,
                            requireNotNull(codes?.singleOrNull()),
                            redirectUri,
                            verifier,
                        )
                        val token = MicrosoftOAuthApi(httpClient).tokenWithAuthorizationCode(request)
                        call.respondText(
                            "Microsoft authorization is complete. Close this page and return to the launcher.",
                            ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                        )
                        terminal.complete(token)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Throwable) {
                        call.respondText(
                            "Microsoft authorization exchange failed. Return to the launcher and try again.",
                            ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                            HttpStatusCode.BadGateway,
                        )
                        terminal.completeExceptionally(
                            IllegalStateException("Microsoft authorization exchange failed"),
                        )
                    }
                }
            }
        }
        try {
            server.startSuspend(wait = false)
            val connector = server.engine.resolvedConnectors().single()
            redirectUri = "http://$OAUTH_REDIRECT_HOST:${connector.port}$OAUTH_REDIRECT_PATH"
            val authorizationUrl = MicrosoftOAuthTools.authorizationUrl(
                MICROSOFT_CLIENT_ID,
                redirectUri,
                state,
                verifier,
            )
            onProgress(MicrosoftLoginStage.WAITING_FOR_BROWSER)
            browserService.open(authorizationUrl.toString())
            return terminal.await()
        } finally {
            withContext(NonCancellable) {
                server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
            }
        }
    }
}

private data class MinecraftLoginResult(
    val identity: MinecraftOnlineIdentity,
    val expiresIn: Long,
)

internal class AccountLoginExpiredException(
    accountName: String,
    cause: Throwable? = null,
) : IllegalStateException("Login expired for $accountName", cause)

private const val TOKEN_REFRESH_SAFETY_SECONDS = 120L
