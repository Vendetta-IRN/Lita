package com.lita.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lita.app.crypto.ChunkedCipher
import com.lita.app.crypto.VaultCrypto
import com.lita.app.crypto.RecoveryFile
import com.lita.app.security.DeviceKeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Run on a device/emulator: verifies the on-device crypto providers and the Android Keystore wrapper. */
@RunWith(AndroidJUnit4::class)
class DeviceCryptoInstrumentedTest {
    @Test fun keystoreWrapRoundTrip() {
        val secret = VaultCrypto.newMasterKey()
        assertArrayEquals(secret, DeviceKeyStore.unwrap(DeviceKeyStore.wrap(secret)))
    }

    @Test fun chunkedCipherRoundTripOnDeviceProvider() {
        val key = VaultCrypto.newMasterKey()
        val data = Random(1).nextBytes(1_000_000)
        val enc = ByteArrayOutputStream()
        ChunkedCipher.encrypt(key, ByteArrayInputStream(data), data.size.toLong(), enc)
        val dec = ByteArrayOutputStream()
        ChunkedCipher.decrypt(key, ByteArrayInputStream(enc.toByteArray()), dec)
        assertArrayEquals(data, dec.toByteArray())
    }

    @Test fun recoveryFileRoundTripWithProductionIterations() {
        val mk = VaultCrypto.newMasterKey()
        val f = RecoveryFile.create(mk, "a long recovery password".toCharArray(), 1)
        assertArrayEquals(mk, RecoveryFile.open(f, "a long recovery password".toCharArray()).masterKey)
    }
}
