package ae.gov.dubaicustoms.platform.storage.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import ae.gov.dubaicustoms.platform.storage.ObjectRef;
import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.ObjectSummary;
import ae.gov.dubaicustoms.platform.storage.StoredObject;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.InputStream;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Wraps every store operation in a micrometer {@link Observation} named {@code dc.platform.storage}
 * with {@code operation} and {@code provider} low-cardinality tags. Exists so storage latency and
 * error rate show up in metrics/tracing without each service instrumenting the calls itself.
 *
 * <p>For {@code get}/{@code list} the observation spans the call that returns the stream, not the
 * caller's subsequent consumption of it.
 */
public final class ObservedObjectStore implements DelegatingObjectStore {

    private static final String NAME = "dc.platform.storage";

    private final ObjectStore delegate;
    private final ObservationRegistry registry;
    private final String provider;

    public ObservedObjectStore(ObjectStore delegate, ObservationRegistry registry, String provider) {
        this.delegate = delegate;
        this.registry = registry;
        this.provider = provider;
    }

    @Override
    public ObjectStore delegate() {
        return delegate;
    }

    @Override
    public ObjectRef put(String bucket, String key, InputStream in, ObjectMetadata meta) {
        return observe("put", () -> delegate.put(bucket, key, in, meta));
    }

    @Override
    public Optional<StoredObject> get(String bucket, String key) {
        return observe("get", () -> delegate.get(bucket, key));
    }

    @Override
    public boolean delete(String bucket, String key) {
        return observe("delete", () -> delegate.delete(bucket, key));
    }

    @Override
    public Stream<ObjectSummary> list(String bucket, String prefix) {
        return observe("list", () -> delegate.list(bucket, prefix));
    }

    private <T> T observe(String operation, java.util.function.Supplier<T> action) {
        return Observation.createNotStarted(NAME, registry)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("provider", provider)
                .observe(action);
    }
}
