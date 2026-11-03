# Nostr SDK

Read [README.md](README.md) first: modules, boundaries, and test commands.

- Never make a Nostr module depend on Rayl code; `verifyModuleDependencies`
  rejects it. Rayl-specific behaviour belongs in `providers/nwc`.
- Keep the `io.github.nicolals.*` packages and module names. Rayl, its ProGuard
  rules, and its distribution records refer to them.
- Typed event views must be cheap to construct: never decrypt or parse in a
  constructor; use lazy properties.
- Verify untrusted events (`EventVerifier`) before using them as authenticated
  input.
- Pass codecs and cipher/signing capabilities explicitly. Don't reintroduce a
  shared module context.
- Install NIP modules in dependency order (for example `nip01` before `nip42`).
- Crypto changes need `testAndroidHostTest` and `iosSimulatorArm64Test`: the
  platform providers differ.
