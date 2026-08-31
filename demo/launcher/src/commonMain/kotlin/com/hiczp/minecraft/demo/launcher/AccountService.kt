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
    private val launcherStore: LauncherStore,
    private val browserService: BrowserService = SystemBrowserService,
) {
    private val refreshStateMutex = Mutex()
    private val refreshLocks = mutableMapOf<Uuid, Mutex>()
    private val failedRefreshes = mutableSetOf<Uuid>()

    suspend fun addOffline(name: String): StoredAccount {
        val validatedName = name.trim()
        val minecraftOfflineIdentity = MinecraftOfflineIdentity(validatedName)
        val storedAccount = StoredAccount(minecraftIdentity = minecraftOfflineIdentity)
        launcherStore.authMemory.update {
            selectedIdentityId = minecraftOfflineIdentity.id
            accounts = accounts.filterNot { it.minecraftIdentity.id == minecraftOfflineIdentity.id } + storedAccount
        }
        clearRefreshFailure(minecraftOfflineIdentity.id)
        return storedAccount
    }

    suspend fun updateOffline(identityId: Uuid, name: String): StoredAccount {
        val replacement = MinecraftOfflineIdentity(name.trim())
        return requireNotNull(
            updateAccountIfPresent(identityId) { storedAccount ->
                require(storedAccount.minecraftIdentity is MinecraftOfflineIdentity) { "Only offline identities can be renamed" }
                storedAccount.copy(minecraftIdentity = replacement)
            },
        ) { "Account does not exist" }
    }

    suspend fun select(identityId: Uuid) {
        launcherStore.authMemory.update {
            require(accounts.any { it.minecraftIdentity.id == identityId }) { "Account does not exist" }
            selectedIdentityId = identityId
        }
    }

    suspend fun delete(identityId: Uuid) {
        launcherStore.authMemory.update {
            accounts = accounts.filterNot { it.minecraftIdentity.id == identityId }
            if (selectedIdentityId == identityId) selectedIdentityId = null
        }
        clearRefreshFailure(identityId)
    }

    suspend fun loginMicrosoft(
        replacingIdentityId: Uuid? = null,
        onProgress: (MicrosoftLoginStage) -> Unit = {},
    ): StoredAccount {
        onProgress(MicrosoftLoginStage.STARTING_CALLBACK)
        val microsoftTokenResponse = receiveAuthorizationCode(onProgress)
        onProgress(MicrosoftLoginStage.VERIFYING_ACCOUNT)
        val minecraftLoginResult = completeMinecraftLogin(microsoftTokenResponse)
        val refreshToken = requireNotNull(microsoftTokenResponse.refreshToken) {
            "Microsoft did not return a refresh token; check the public client and offline_access configuration"
        }
        val storedAccount = StoredAccount(
            minecraftIdentity = minecraftLoginResult.minecraftOnlineIdentity,
            microsoftRefreshToken = refreshToken,
            minecraftAccessTokenExpiresAtEpochSeconds = Clock.System.now().epochSeconds + minecraftLoginResult.expiresIn,
        )
        val lockIdentityId = replacingIdentityId ?: storedAccount.minecraftIdentity.id
        refreshLock(lockIdentityId).withLock {
            launcherStore.authMemory.update {
                if (replacingIdentityId == null) {
                    selectedIdentityId = storedAccount.minecraftIdentity.id
                    accounts =
                        accounts.filterNot { it.minecraftIdentity.id == storedAccount.minecraftIdentity.id } + storedAccount
                } else {
                    val index = accounts.indexOfFirst { it.minecraftIdentity.id == replacingIdentityId }
                    require(index >= 0) { "Account does not exist" }
                    require(accounts[index].minecraftIdentity is MinecraftOnlineIdentity) {
                        "Only Microsoft accounts can be signed in again"
                    }
                    require(storedAccount.minecraftIdentity.id == replacingIdentityId) {
                        "The signed-in Minecraft profile does not match the account being updated"
                    }
                    accounts = accounts.toMutableList().apply { this[index] = storedAccount }
                }
            }
            clearRefreshFailure(lockIdentityId)
        }
        onProgress(MicrosoftLoginStage.COMPLETE)
        return storedAccount
    }

    fun selectedAccount(): StoredAccount? = launcherStore.authMemory.read {
        accounts.singleOrNull { it.minecraftIdentity.id == selectedIdentityId }
    }

    fun selectedIdentity(): MinecraftIdentity =
        selectedAccount()?.minecraftIdentity ?: MinecraftOfflineIdentity(DEFAULT_OFFLINE_PLAYER_NAME)

    fun needsRefresh(storedAccount: StoredAccount, nowEpochSeconds: Long = Clock.System.now().epochSeconds): Boolean {
        if (storedAccount.minecraftIdentity !is MinecraftOnlineIdentity) return false
        val expiresAt = storedAccount.minecraftAccessTokenExpiresAtEpochSeconds ?: return true
        return expiresAt <= nowEpochSeconds + TOKEN_REFRESH_SAFETY_SECONDS
    }

    suspend fun refreshIfNeeded(identityId: Uuid): StoredAccount? = refreshLock(identityId).withLock {
        val storedAccount =
            launcherStore.authMemory.read { accounts.singleOrNull { it.minecraftIdentity.id == identityId } }
                ?: return@withLock null
        val minecraftOnlineIdentity =
            storedAccount.minecraftIdentity as? MinecraftOnlineIdentity ?: return@withLock storedAccount
        if (!needsRefresh(storedAccount)) {
            clearRefreshFailure(identityId)
            return@withLock storedAccount
        }
        if (refreshStateMutex.withLock { identityId in failedRefreshes }) {
            throw AccountLoginExpiredException(minecraftOnlineIdentity.name)
        }

        try {
            val refreshed = refreshOnlineAccount(storedAccount, minecraftOnlineIdentity)
            clearRefreshFailure(identityId)
            refreshed
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (launcherStore.authMemory.read { accounts.none { it.minecraftIdentity.id == identityId } }) {
                clearRefreshFailure(identityId)
                return@withLock null
            }
            refreshStateMutex.withLock { failedRefreshes += identityId }
            throw AccountLoginExpiredException(minecraftOnlineIdentity.name, failure)
        }
    }

    private suspend fun refreshOnlineAccount(
        storedAccount: StoredAccount,
        minecraftOnlineIdentity: MinecraftOnlineIdentity,
    ): StoredAccount? {
        val refreshToken = requireNotNull(storedAccount.microsoftRefreshToken)
        val microsoftTokenResponse = MicrosoftOAuthApi(httpClient).tokenWithRefreshToken(
            MicrosoftOAuthTools.refreshTokenRequest(MICROSOFT_CLIENT_ID, refreshToken),
        )
        val rotatedRefreshToken = microsoftTokenResponse.refreshToken ?: refreshToken
        if (rotatedRefreshToken != refreshToken) {
            updateAccountIfPresent(minecraftOnlineIdentity.id) { it.copy(microsoftRefreshToken = rotatedRefreshToken) }
                ?: return null
        }
        val minecraftLoginResult = completeMinecraftLogin(microsoftTokenResponse)
        require(minecraftLoginResult.minecraftOnlineIdentity.id == minecraftOnlineIdentity.id) {
            "The refreshed Minecraft profile does not match the stored account"
        }
        return updateAccountIfPresent(minecraftOnlineIdentity.id) {
            it.copy(
                minecraftIdentity = minecraftLoginResult.minecraftOnlineIdentity,
                microsoftRefreshToken = rotatedRefreshToken,
                minecraftAccessTokenExpiresAtEpochSeconds = Clock.System.now().epochSeconds + minecraftLoginResult.expiresIn,
            )
        }
    }

    private suspend fun updateAccountIfPresent(
        identityId: Uuid,
        transform: (StoredAccount) -> StoredAccount,
    ): StoredAccount? {
        var updated: StoredAccount? = null
        launcherStore.authMemory.update {
            val index = accounts.indexOfFirst { it.minecraftIdentity.id == identityId }
            if (index < 0) return@update
            val replacement = transform(accounts[index])
            updated = replacement
            if (selectedIdentityId == identityId) selectedIdentityId = replacement.minecraftIdentity.id
            accounts = accounts.toMutableList().apply { this[index] = replacement }
        }
        return updated
    }

    private suspend fun refreshLock(identityId: Uuid): Mutex = refreshStateMutex.withLock {
        refreshLocks.getOrPut(identityId) { Mutex() }
    }

    private suspend fun clearRefreshFailure(identityId: Uuid) = refreshStateMutex.withLock {
        failedRefreshes -= identityId
    }

    private suspend fun completeMinecraftLogin(microsoftTokenResponse: MicrosoftTokenResponse): MinecraftLoginResult {
        val xboxAuthenticationApi = XboxAuthenticationApi(httpClient)
        val userToken =
            xboxAuthenticationApi.authenticateUser(
                XboxAuthenticationTools.userAuthenticationRequest(
                    microsoftTokenResponse
                )
            )
        val xstsToken = xboxAuthenticationApi.authorizeXsts(XboxAuthenticationTools.xstsAuthorizationRequest(userToken))
        val minecraftServicesApi = MinecraftServicesApi(httpClient)
        val minecraftLoginResponse =
            minecraftServicesApi.loginWithXbox(MinecraftServicesTools.xboxLoginRequest(xstsToken))
        val minecraftEntitlementsResponse =
            minecraftServicesApi.getStoreEntitlements(minecraftLoginResponse.accessToken)
        require(MinecraftServicesTools.hasJavaEditionEntitlement(minecraftEntitlementsResponse)) {
            "This Microsoft account does not own Minecraft: Java Edition"
        }
        val minecraftProfileResponse = minecraftServicesApi.getMinecraftProfile(minecraftLoginResponse.accessToken)
        val minecraftOnlineIdentity =
            MinecraftOnlineIdentity(
                Uuid.parse(minecraftProfileResponse.id),
                minecraftProfileResponse.name,
                minecraftLoginResponse.accessToken
            )
        return MinecraftLoginResult(minecraftOnlineIdentity, minecraftLoginResponse.expiresIn)
    }

    private suspend fun receiveAuthorizationCode(onProgress: (MicrosoftLoginStage) -> Unit): MicrosoftTokenResponse {
        val state = MicrosoftOAuthTools.generateState()
        val verifier = MicrosoftOAuthTools.generateCodeVerifier()
        val terminal = CompletableDeferred<MicrosoftTokenResponse>()
        val terminalOwner = Mutex()
        var redirectUri = ""
        val embeddedServer = embeddedServer(CIO, host = OAUTH_REDIRECT_HOST, port = 0) {
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
                        val microsoftAuthorizationCodeTokenRequest = MicrosoftOAuthTools.authorizationCodeTokenRequest(
                            MICROSOFT_CLIENT_ID,
                            requireNotNull(codes?.singleOrNull()),
                            redirectUri,
                            verifier,
                        )
                        val microsoftTokenResponse =
                            MicrosoftOAuthApi(httpClient).tokenWithAuthorizationCode(
                                microsoftAuthorizationCodeTokenRequest
                            )
                        call.respondText(
                            "Microsoft authorization is complete. Close this page and return to the launcher.",
                            ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                        )
                        terminal.complete(microsoftTokenResponse)
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
            embeddedServer.startSuspend(wait = false)
            val engineConnectorConfig = embeddedServer.engine.resolvedConnectors().single()
            redirectUri = "http://$OAUTH_REDIRECT_HOST:${engineConnectorConfig.port}$OAUTH_REDIRECT_PATH"
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
                embeddedServer.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
            }
        }
    }
}

private data class MinecraftLoginResult(
    val minecraftOnlineIdentity: MinecraftOnlineIdentity,
    val expiresIn: Long,
)

internal class AccountLoginExpiredException(
    accountName: String,
    cause: Throwable? = null,
) : IllegalStateException("Login expired for $accountName", cause)

private const val TOKEN_REFRESH_SAFETY_SECONDS = 120L
