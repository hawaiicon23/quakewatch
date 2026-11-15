package dev.blx.btprobe;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Optional: makes this phone an alert authority. The private key is created inside
 * the Android Keystore and never leaves the phone; only its public key is exported
 * (to be added to res/raw/trusted_keys.txt on every phone).
 */
final class PhoneAuthority {
    private static final String ALIAS="quake_authority";

    private static KeyStore store() throws GeneralSecurityException {
        try { KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null); return ks; }
        catch(java.io.IOException e) { throw new GeneralSecurityException(e); }
    }
    static boolean exists() {
        try { return store().containsAlias(ALIAS); } catch(GeneralSecurityException e) { return false; }
    }
    static PublicKey create() throws GeneralSecurityException {
        KeyPairGenerator g=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).build());
        return g.generateKeyPair().getPublic();
    }
    static PublicKey publicKey() throws GeneralSecurityException {
        return store().getCertificate(ALIAS).getPublicKey();
    }
    /** Line for trusted_keys.txt: "name base64(SubjectPublicKeyInfo)". */
    static String trustedLine(String name) throws GeneralSecurityException {
        return name.replaceAll("\\s+","-")+" "+Base64.getEncoder().encodeToString(publicKey().getEncoded());
    }
    /** Raw 64-byte r||s signature of the alert message. */
    static byte[] sign(byte[] message) throws GeneralSecurityException {
        PrivateKey key=(PrivateKey)store().getKey(ALIAS,null);
        Signature s=Signature.getInstance("SHA256withECDSA"); s.initSign(key); s.update(message);
        return SignedAlerts.derToRaw(s.sign());
    }
}
