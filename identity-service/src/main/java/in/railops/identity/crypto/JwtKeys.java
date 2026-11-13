package in.railops.identity.crypto;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Signing key plus previously-used public keys.
 *
 * <p>Rotation without logging everyone out: (1) deploy the new private key as current and the old
 * public key in {@code previousPublicKeysPem}; resource servers see both in the JWKS and accept both;
 * (2) after the longest access-token lifetime (10 min) plus JWKS cache time, drop the old key.
 * The kid is the RFC 7638 thumbprint, so it is derived from the key, never hand-typed.
 */
public final class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    private final RSAKey signingKey;
    private final JWKSet publicSet;

    private JwtKeys(RSAKey signingKey, List<RSAKey> previous) {
        this.signingKey = signingKey;
        List<JWK> pub = new ArrayList<>();
        pub.add(signingKey.toPublicJWK());
        previous.forEach(k -> pub.add(k.toPublicJWK()));
        this.publicSet = new JWKSet(pub);
    }

    public static JwtKeys fromPem(String privateKeyPkcs8Pem, List<String> previousPublicKeysPem) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(
                    new PKCS8EncodedKeySpec(der(privateKeyPkcs8Pem, "PRIVATE KEY")));
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                    new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
            if (pub.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("JWT signing key must be at least 2048 bits");
            }
            List<RSAKey> previous = new ArrayList<>();
            for (String pem : previousPublicKeysPem) {
                if (!pem.isBlank()) {
                    previous.add(withThumbprint(new RSAKey.Builder(
                            (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(der(pem, "PUBLIC KEY"))))));
                }
            }
            return new JwtKeys(withThumbprint(new RSAKey.Builder(pub).privateKey(priv)), previous);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("invalid JWT key material", e);
        }
    }

    /** LOCAL PROFILE ONLY: a fresh key per process; every restart invalidates all tokens. */
    public static JwtKeys generateEphemeral() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            log.warn("Using an EPHEMERAL JWT signing key. Never acceptable outside local development.");
            return new JwtKeys(withThumbprint(new RSAKey.Builder((RSAPublicKey) kp.getPublic())
                    .privateKey(kp.getPrivate())), List.of());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public RSAKey signingKey() {
        return signingKey;
    }

    public JWKSet publicJwkSet() {
        return publicSet;
    }

    private static RSAKey withThumbprint(RSAKey.Builder b) {
        try {
            RSAKey tmp = b.keyUse(KeyUse.SIGNATURE).build();
            return b.keyID(tmp.computeThumbprint().toString()).build();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] der(String pem, String type) {
        String body = pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
