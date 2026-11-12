package xyz.lilsus.raylsuite.feature.paymenthub.bills

import xyz.lilsus.raylsuite.core.raylapi.TelemetryFakeDoor

enum class HubBillKind { Water, Electricity, Telecom }

/**
 * A Salvadoran bill the Hub offers before it can pay it, to learn which ones are wanted: a fake
 * door. It is never placed as a widget; a tap only says that paying it is on its way. [title] is
 * the biller's own name and is not translated.
 */
enum class HubBill(val title: String, val kind: HubBillKind, val door: TelemetryFakeDoor) {
    Anda("ANDA", HubBillKind.Water, TelemetryFakeDoor.BillAnda),
    Caess("AES CAESS", HubBillKind.Electricity, TelemetryFakeDoor.BillCaess),
    Delsur("Delsur", HubBillKind.Electricity, TelemetryFakeDoor.BillDelsur),
    Clesa("AES CLESA", HubBillKind.Electricity, TelemetryFakeDoor.BillClesa),
    Eeo("AES EEO", HubBillKind.Electricity, TelemetryFakeDoor.BillEeo),
    Deusem("AES DEUSEM", HubBillKind.Electricity, TelemetryFakeDoor.BillDeusem),
    Tigo("Tigo", HubBillKind.Telecom, TelemetryFakeDoor.BillTigo),
    ClaroResidencial(
        "Claro Residencial",
        HubBillKind.Telecom,
        TelemetryFakeDoor.BillClaroResidencial
    )
}
