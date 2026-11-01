package xyz.lilsus.rayl.providers.spark.feature.walletconnection

import xyz.lilsus.rayl.providers.spark.application.wallet.RecoveryPhraseWords
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeSparkWalletConnectionText(
    val importTitle: String,
    val importBody: String,
    val phraseLabel: String,
    /** "Word 1" … "Word 24", for each field's accessibility label. */
    val wordLabels: List<String>,
    /** Labels for [RecoveryPhraseWords.OFFERED_COUNTS], e.g. "12 words". */
    val wordCountOptions: List<String>,
    val wordsWrong: String,
    val storageNote: String,
    val passphraseToggle: String,
    val passphraseLabel: String,
    val passphraseHint: String,
    val passphraseNote: String,
    val importAction: String,
    val removeTitle: String,
    val removeBody: String,
    val removeConfirm: String,
    val cancel: String,
    val retry: String,
    val resetAction: String,
    val loading: String,
    val connecting: String,
    val removing: String,
    val working: String,
    val reconnectTitle: String,
    val reconnectBody: String,
    val resetTitle: String,
    val resetBody: String,
    val credentialTitle: String,
    val credentialUnavailable: String,
    val credentialInvalidated: String,
    val credentialCorrupt: String,
    val errorAlreadyConfigured: String,
    val errorInvalidMnemonic: String,
    val errorConnection: String,
    val errorStorage: String,
    val errorReset: String
)

suspend fun nativeSparkWalletConnectionText(appName: String): NativeSparkWalletConnectionText =
    NativeSparkWalletConnectionText(
        importTitle = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_import_title")
        ),
        importBody = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_import_body"),
            appName
        ),
        phraseLabel = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_phrase_label")
        ),
        wordLabels = (1..RecoveryPhraseWords.OFFERED_COUNTS.max()).map {
            nativeString(
                NativeStringResource(
                    table = "SparkWalletConnection",
                    key = "spark_wallet_word_label"
                ),
                it
            )
        },
        wordCountOptions = RecoveryPhraseWords.OFFERED_COUNTS.map {
            nativeString(
                NativeStringResource(
                    table = "SparkWalletConnection",
                    key = "spark_wallet_word_count"
                ),
                it
            )
        },
        wordsWrong = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_words_wrong")
        ),
        passphraseToggle = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_passphrase_toggle"
            )
        ),
        passphraseLabel = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_passphrase_label"
            )
        ),
        passphraseHint = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_passphrase_hint"
            )
        ),
        passphraseNote = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_passphrase_note"
            )
        ),
        storageNote = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_storage_note")
        ),
        importAction = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_import_action"
            )
        ),
        removeTitle = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_remove_title"
            ),
            appName
        ),
        removeBody = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_remove_body")
        ),
        removeConfirm = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_remove_confirm"
            )
        ),
        cancel = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_cancel")
        ),
        retry = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_retry")
        ),
        resetAction = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_reset_action"
            ),
            appName
        ),
        loading = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_progress_loading"
            )
        ),
        connecting = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_progress_connecting"
            )
        ),
        removing = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_progress_removing"
            )
        ),
        working = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_progress_working"
            )
        ),
        reconnectTitle = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_reconnect_title"
            )
        ),
        reconnectBody = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_reconnect_body"
            ),
            appName
        ),
        resetTitle = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_reset_title")
        ),
        resetBody = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_reset_body")
        ),
        credentialTitle = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_credential_title"
            )
        ),
        credentialUnavailable = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_credential_unavailable"
            )
        ),
        credentialInvalidated = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_credential_invalidated"
            )
        ),
        credentialCorrupt = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_credential_corrupt"
            )
        ),
        errorAlreadyConfigured = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_error_already_configured"
            )
        ),
        errorInvalidMnemonic = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_error_invalid_mnemonic"
            )
        ),
        errorConnection = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_error_connection"
            )
        ),
        errorStorage = nativeString(
            NativeStringResource(
                table = "SparkWalletConnection",
                key = "spark_wallet_error_storage"
            )
        ),
        errorReset = nativeString(
            NativeStringResource(table = "SparkWalletConnection", key = "spark_wallet_error_reset")
        )
    )
