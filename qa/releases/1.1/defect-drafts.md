# 1.1 defect drafts

Reports ready to file as Linear issues (master plan §10). Once filed, replace the draft with
the issue link in [status.md](status.md) and delete it here.

## Auto Pay threshold copy says the threshold amount pays automatically, but it asks for confirmation

- **Labels:** `bug`, `S4`, `PR-02`. Observation O-01; the intended behaviour is decided in
  D-06a.
- **Build:** `main` at `c3c00e81`. Android and iOS, all locales.
- **Expected:** a payment of exactly the threshold asks for confirmation (decided: the code
  is right). The copy should say so.
- **Actual:** two strings say the opposite:

  | Key | en | de | es |
  |---|---|---|---|
  | `settings_payments_confirm_threshold` (Settings → Payments) | Above %1$s | Über %1$s | Por encima de %1$s |
  | `onboarding_autopay_threshold_label` (onboarding) | Auto-pay up to %1$s | Auto-Pay bis %1$s | Auto-pagar hasta %1$s |

  Files: `feature/payment-settings/src/androidMain/res/values*/strings.xml`,
  `feature/payment-settings/src/iosMain/resources/PaymentSettings.xcstrings`, and the
  matching files in `feature/onboarding`.
- **Steps:** keep the default (confirm above 10,000 sats); scan a 10,000-sat invoice. The
  confirmation screen appears, although Settings says "Above 10,000 sats".
- **Evidence:** `PaymentConfirmationPolicy` uses `amountSats >= thresholdSats`;
  `PaymentConfirmationPolicyTest.confirmationStartsAtTheExactThresholdForEverySelectableStep`
  pins this boundary.
- **Fix hint:** wording such as "From %1$s" / "Ab %1$s" / "Desde %1$s", and "Auto-pay
  below %1$s" for onboarding. The fee-aware Auto Pay (SI-01) changes these strings anyway
  (the setting must say fees may be added for Blink and NWC), so fix both together.
- **Severity rationale:** S4. The behaviour is the safe one; only the copy is wrong.
