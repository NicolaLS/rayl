import Foundation
import Shared

func configureRaylSpark() {
#if DEBUG
    RaylEarlyStartKt.enableRaylTimings()
    let debugTools = true
#else
    let debugTools = false
#endif
    // Anonymous usage data: sent by the store app only. Debug builds log it under
    // `RaylTelemetry`; automated builds must not report their runs as usage.
#if RAYL_E2E
    let usageData = "off"
#elseif DEBUG
    let usageData = "log"
#else
    let usageData = "upload"
#endif
    // Before `RaylIosApp` exists: creating it already reports the first onboarding step.
    RaylTelemetryKt.configureRaylTelemetry(
        mode: usageData,
        appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
    )
#if RAYL_E2E
    // Simulators in automated runs cannot complete passkey ceremonies: use the stand-in passkey.
    let passkeys: RaylPasskeyAuthenticator? = nil
#else
    let passkeys: RaylPasskeyAuthenticator? = RaylPasskeyBridge(rpId: passkeyRelyingParty())
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

/// The passkey relying party, `RAYL_PASSKEY_RP_ID` in the build configuration, which the
/// entitlements name too (`webcredentials:`): Breez's shared one in Release builds and Rayl's own
/// (www.nicolasusca.com, whose site lives in the nicolasusca.com repository) in debug builds.
/// Changing it changes every user's wallet.
private func passkeyRelyingParty() -> String {
    let rpId = (Bundle.main.object(forInfoDictionaryKey: "RaylPasskeyRPID") as? String)?
        .trimmingCharacters(in: .whitespacesAndNewlines)
    guard let rpId, !rpId.isEmpty else {
        fatalError("Set RAYL_PASSKEY_RP_ID in the build configuration")
    }
    return rpId
}
