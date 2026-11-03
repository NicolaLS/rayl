// Host tests run on the desktop JVM, but the Android builds of libsodium and secp256k1 ship only
// Android binaries. Every Nostr module's host tests use their JVM builds instead.
subprojects {
    configurations.matching { it.name == "androidHostTestRuntimeClasspath" }.configureEach {
        exclude(
            group = "com.ionspin.kotlin",
            module = "multiplatform-crypto-libsodium-bindings-android"
        )
    }
    configurations.matching { it.name == "androidHostTestRuntimeOnly" }.configureEach {
        dependencies.addLater(rootProject.libs.ionspin.libsodium.jvm)
        dependencies.addLater(rootProject.libs.secp256k1.kmp.jni.jvm)
    }
}
