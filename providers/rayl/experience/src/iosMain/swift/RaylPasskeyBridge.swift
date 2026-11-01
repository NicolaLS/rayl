import AuthenticationServices
import BreezSdkSpark
import Foundation
import UIKit
import Shared

/// Rayl's passkeys on iOS: forwards the Kotlin pocket's passkey calls to Breez's Swift
/// `PasskeyProvider`, which runs the AuthenticationServices ceremonies for `rpId`.
///
/// The Kotlin and Swift SDK bindings share one native library, so this only calls the provider's
/// own methods, never the Swift SDK's `PasskeyClient` or anything else that crosses into it.
final class RaylPasskeyBridge: NSObject, RaylPasskeyAuthenticator {
    private let provider: BreezSdkSpark.PasskeyProvider

    init(rpId: String) {
        provider = BreezSdkSpark.PasskeyProvider(
            options: BreezSdkSpark.PasskeyProviderOptions(
                rpId: rpId,
                rpName: Self.name,
                userName: Self.name,
                userDisplayName: Self.name
            ),
            iosOptions: BreezSdkSpark.IOSOptions(anchorProvider: MainThreadPresentationAnchor())
        )
    }

    func deriveSeeds(
        salts: [String],
        allowCredentials: [Data],
        preferImmediatelyAvailable: KotlinBoolean?,
        completion: @escaping (RaylPasskeySeeds?, RaylPasskeyError?) -> Void
    ) {
        let request = BreezSdkSpark.DeriveSeedsRequest(
            salts: salts,
            allowCredentials: allowCredentials,
            preferImmediatelyAvailableCredentials: preferImmediatelyAvailable?.boolValue
        )
        Task {
            do {
                let output = try await provider.deriveSeeds(request: request)
                completion(
                    RaylPasskeySeeds(seeds: output.seeds, credentialId: output.credentialId),
                    nil
                )
            } catch {
                completion(nil, Self.passkeyError(error))
            }
        }
    }

    func createPasskey(
        excludeCredentials: [Data],
        salts: [String],
        completion: @escaping (RaylPasskeyCreated?, RaylPasskeyError?) -> Void
    ) {
        Task {
            do {
                let output = try await provider.createPasskey(
                    excludeCredentials: excludeCredentials,
                    salts: salts
                )
                let credential = output.credential
                completion(
                    RaylPasskeyCreated(
                        credentialId: credential.credentialId,
                        userId: credential.userId,
                        aaguid: credential.aaguid,
                        backupEligible: credential.backupEligible.map { KotlinBoolean(value: $0) },
                        seeds: output.seeds
                    ),
                    nil
                )
            } catch {
                completion(nil, Self.passkeyError(error))
            }
        }
    }

    func checkDomainAssociation(completion: @escaping (RaylPasskeyDomain) -> Void) {
        Task {
            do {
                switch try await provider.checkDomainAssociation() {
                case .associated:
                    completion(RaylPasskeyDomain(kind: .associated, source: "", reason: ""))
                case .notAssociated(let source, let reason):
                    completion(RaylPasskeyDomain(kind: .notassociated, source: source, reason: reason))
                case .skipped(let reason):
                    completion(RaylPasskeyDomain(kind: .skipped, source: "", reason: reason))
                }
            } catch {
                completion(RaylPasskeyDomain(kind: .skipped, source: "", reason: "\(error)"))
            }
        }
    }

    /// How the passkey is named in the system's passkey manager.
    private static let name = "Rayl"

    private static func passkeyError(_ error: Error) -> RaylPasskeyError {
        guard let prf = error as? BreezSdkSpark.PrfProviderError else {
            return RaylPasskeyError(kind: .generic, message: "\(error)")
        }
        switch prf {
        case .PrfNotSupported:
            return RaylPasskeyError(kind: .prfnotsupported, message: "PRF is not supported")
        case .UserCancelled:
            return RaylPasskeyError(kind: .usercancelled, message: "Cancelled")
        case .UserTimedOut:
            return RaylPasskeyError(kind: .usertimedout, message: "Timed out")
        case .CredentialNotFound(let message):
            return RaylPasskeyError(kind: .credentialnotfound, message: message)
        case .AuthenticationFailed(let message):
            return RaylPasskeyError(kind: .authenticationfailed, message: message)
        case .PrfEvaluationFailed(let message):
            return RaylPasskeyError(kind: .prfevaluationfailed, message: message)
        case .Configuration(let message):
            return RaylPasskeyError(kind: .configuration, message: message)
        case .CredentialAlreadyExists(let message):
            return RaylPasskeyError(kind: .credentialalreadyexists, message: message)
        case .Generic(let message):
            return RaylPasskeyError(kind: .generic, message: message)
        }
    }
}

/// The key window for the passkey sheet. Breez asks for it on the thread that starts the ceremony,
/// which is not the main thread here, and its default reads UIKit state there; this reads it on
/// the main thread instead.
private final class MainThreadPresentationAnchor: BreezSdkSpark.PasskeyPresentationAnchorProvider {
    func presentationAnchor() -> ASPresentationAnchor {
        if Thread.isMainThread { return MainActor.assumeIsolated { Self.keyWindow() } }
        return DispatchQueue.main.sync { MainActor.assumeIsolated { Self.keyWindow() } }
    }

    @MainActor
    private static func keyWindow() -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.first { $0.activationState == .foregroundActive }
        return active?.windows.first(where: \.isKeyWindow)
            ?? scenes.flatMap(\.windows).first
            ?? ASPresentationAnchor()
    }
}

