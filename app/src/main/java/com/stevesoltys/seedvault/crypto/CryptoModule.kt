package com.stevesoltys.seedvault.crypto

import org.koin.dsl.module
import java.security.KeyStore

private const val ANDROID_KEY_STORE = "AndroidKeyStore"

val cryptoModule = module {
    factory<CipherFactory> { CipherFactoryImpl(get()) }
    single<KeyManager> {
        val keyStore by lazy {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
            }
        }
        KeyManagerImpl(keyStore)
    }
    single<Crypto> { CryptoImpl(get(), get(), get()) }
}
