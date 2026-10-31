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
/// every user's wallet, so it must be final before release.
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
#else
    let passkeys: RaylPasskeyAuthenticator? = RaylPasskeyBridge(rpId: developmentRelyingParty)
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
