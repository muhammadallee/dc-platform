package ae.gov.dubaicustoms.example.extension;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectRef;
import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.ObjectStoreException;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A custom {@link ObjectStore} provider that encrypts object content at rest and decrypts it on read,
 * delegating the actual persistence to any wrapped {@code ObjectStore} (here, the platform's
 * {@code FsObjectStore}). It exists to demonstrate the platform's <b>extension model</b>: a team ships
 * its own provider plus an auto-configuration that registers it <em>before</em> the platform default,
 * and the default backs off — see {@code EncryptingStorageAutoConfiguration}. The provider is
 * platform-certified because {@code EncryptingFsObjectStoreTckTest} passes the storage TCK against it.
 *
 * <p><b>How the round-trip stays honest.</b> Encryption uses AES in CTR mode, which is
 * length-preserving: the ciphertext is exactly as long as the plaintext, so the delegate's stored
 * size, the {@link ObjectRef#size()} it returns, and the {@link ObjectMetadata#contentLength()} it
 * persists all continue to reflect the plaintext length. The per-object random initialisation vector
 * is stored as a reserved user-tag ({@value #IV_TAG}) alongside the object and stripped from the
 * metadata handed back to callers.
 *
 * <p><b>Thread-safety:</b> immutable (a key and a delegate); a fresh {@link Cipher} is created per
 * call, so concurrent operations do not share cipher state.
 *
 * <p><b>Security note:</b> this is illustrative. A production provider would derive per-object keys,
 * authenticate ciphertext (e.g. AES-GCM with the key/nonce managed by the platform secrets source),
 * and never keep a single static key in configuration.
 */
public final class EncryptingFsObjectStore implements ObjectStore {

    /** Reserved user-tag holding the base64 per-object IV; never surfaced to callers. */
    static final String IV_TAG = "__enc_iv";

    private static final String TRANSFORMATION = "AES/CTR/NoPadding";
    private static final int IV_BYTES = 16;

    private final ObjectStore delegate;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /**
     * Wraps {@code delegate}, encrypting content with {@code key}.
     *
     * @param delegate the backing store that persists ciphertext; never {@code null}
     * @param key the AES key; never {@code null}
     */
    public EncryptingFsObjectStore(ObjectStore delegate, SecretKey key) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.key = Objects.requireNonNull(key, "key must not be null");
    }

    /**
     * Builds an AES key from a base64-encoded 128/192/256-bit secret.
     *
     * @param base64 the base64 key material; never {@code null}
     * @return the AES {@link SecretKey}
     */
    public static SecretKey keyFromBase64(String base64) {
        byte[] material = Base64.getDecoder().decode(Objects.requireNonNull(base64, "base64 must not be null"));
        return new SecretKeySpec(material, "AES");
    }

    /**
     * Generates a fresh 256-bit AES key (used by the TCK; the app configures one instead).
     *
     * @return a new AES {@link SecretKey}
     */
    public static SecretKey generateKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "cannot generate AES key", e);
        }
    }

    @Override
    public ObjectRef put(String bucket, String key, InputStream in, ObjectMetadata meta) {
        Objects.requireNonNull(in, "in must not be null");
        Objects.requireNonNull(meta, "meta must not be null");

        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        CipherInputStream encrypting = new CipherInputStream(in, cipher(Cipher.ENCRYPT_MODE, iv));

        Map<String, String> tags = new HashMap<>(meta.userTags());
        tags.put(IV_TAG, Base64.getEncoder().encodeToString(iv));
        // CTR is length-preserving, so the delegate's size/contentLength stay equal to the plaintext's.
        return delegate.put(bucket, key, encrypting,
                new ObjectMetadata(meta.contentType(), meta.contentLength(), Map.copyOf(tags)));
    }

    @Override
    public Optional<StoredObject> get(String bucket, String key) {
        return delegate.get(bucket, key).map(stored -> {
            ObjectMetadata stashed = stored.metadata();
            String encodedIv = stashed.userTags().get(IV_TAG);
            if (encodedIv == null) {
                throw new ObjectStoreException(ObjectStoreException.IO,
                        "object " + bucket + "/" + key + " has no encryption IV; not written by this provider");
            }
            byte[] iv = Base64.getDecoder().decode(encodedIv);
            CipherInputStream decrypting = new CipherInputStream(stored.content(), cipher(Cipher.DECRYPT_MODE, iv));

            Map<String, String> cleanTags = new HashMap<>(stashed.userTags());
            cleanTags.remove(IV_TAG);
            ObjectMetadata clean = new ObjectMetadata(stashed.contentType(), stashed.contentLength(), Map.copyOf(cleanTags));
            return new StoredObject(clean, decrypting);
        });
    }

    @Override
    public boolean delete(String bucket, String key) {
        return delegate.delete(bucket, key);
    }

    @Override
    public Stream<ObjectSummary> list(String bucket, String prefix) {
        return delegate.list(bucket, prefix);
    }

    private Cipher cipher(int mode, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new IvParameterSpec(iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new ObjectStoreException(ObjectStoreException.IO, "cipher init failed", e);
        }
    }
}
