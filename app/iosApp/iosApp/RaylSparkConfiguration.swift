import Foundation
import Shared

/// Rayl's own relying party for passkeys while it is not registered with Breez. Its
/// `/.well-known/apple-app-site-association` lists the app, and the app's entitlements name it
/// (`webcredentials:www.nicolasusca.com`). The site lives in the nicolasusca.com repository; the
/// apex domain redirects to `www`, and association files must not redirect.
///
/// TODO(breez): Before release, register the production app with Breez for its shared relying
/// party, then use `PasskeyProvider.BREEZ_RP_ID` ("keys.breez.technology") in Release builds and
/// add `webcredentials:keys.breez.technology` to the entitlements. Send Breez the team ID
/// (TA77463BFV) and bundle ID (com.nicolasusca.rayl). Changing the relying party later changes
/// every user's wallet, so it must be final before release. Until then, Release builds offer only
/// the external wallets (`ReleasePasskeysUnavailable`), like Android release builds.
private let developmentRelyingParty = "www.nicolasusca.com"

func configureRaylSpark() {
#if DEBUG
    RaylEarlyStartKt.enableRaylTimings()
    let debugTools = true
#else
    let debugTools = false
#endif
#if RAYL_E2E
    // Simulators in automated runs cannot complete passkey ceremonies: use the stand-in passkey.
    let passkeys: RaylPasskeyAuthenticator? = nil
#elseif DEBUG
    let passkeys: RaylPasskeyAuthenticator? = RaylPasskeyBridge(rpId: developmentRelyingParty)
#else
    // Wallets derived from the development relying party would change when it does.
    let passkeys: RaylPasskeyAuthenticator? = ReleasePasskeysUnavailable()
#endif
#if RAYL_DEBUG && RAYL_PRODUCTION
#error("Rayl environment conditions are contradictory")
#elseif RAYL_DEBUG
    RaylIosApp.shared.configureSpark(
        environment: SparkEnvironment.debug,
        breezApiKey: nil,
        passkeys: passkeys,
        debugTools: debugTools
    )
#elseif RAYL_PRODUCTION
    let breezApiKey = (Bundle.main.object(forInfoDictionaryKey: "RaylBreezAPIKey") as? String)?
        .trimmingCharacters(in: .whitespacesAndNewlines)
#if DEBUG
    // The Mainnet debug app: fail loudly instead of a pocket that can never connect.
    if breezApiKey?.isEmpty != false {
        fatalError("Set RAYL_BREEZ_API_KEY in your environment (direnv) and rebuild")
    }
#endif
    RaylIosApp.shared.configureSpark(
        environment: SparkEnvironment.production,
        breezApiKey: breezApiKey,
        passkeys: passkeys,
        debugTools: debugTools
    )
#else
#error("A Rayl environment compilation condition is required")
#endif
}

/// Passkeys for Release builds until Rayl has a final relying party: the domain reports as not
/// associated, so Rayl's wallet is unavailable and onboarding offers the external wallets.
private final class ReleasePasskeysUnavailable: NSObject, RaylPasskeyAuthenticator {
    private static let reason = "Rayl has no final passkey relying party yet"

    func deriveSeeds(
        salts: [String],
        allowCredentials: [Data],
        preferImmediatelyAvailable: KotlinBoolean?,
        completion: @escaping (RaylPasskeySeeds?, RaylPasskeyError?) -> Void
    ) {
        completion(nil, RaylPasskeyError(kind: .configuration, message: Self.reason))
    }

    func createPasskey(
        excludeCredentials: [Data],
        salts: [String],
        completion: @escaping (RaylPasskeyCreated?, RaylPasskeyError?) -> Void
    ) {
        completion(nil, RaylPasskeyError(kind: .configuration, message: Self.reason))
    }

    func checkDomainAssociation(completion: @escaping (RaylPasskeyDomain) -> Void) {
        completion(RaylPasskeyDomain(kind: .notassociated, source: "Rayl", reason: Self.reason))
    }
}
