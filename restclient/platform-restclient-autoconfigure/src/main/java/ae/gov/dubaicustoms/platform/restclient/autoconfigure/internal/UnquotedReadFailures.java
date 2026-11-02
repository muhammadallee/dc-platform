package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.restclient.RemoteCallException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.SmartHttpMessageConverter;

/**
 * Wraps a client's message converters so a body that cannot be parsed fails with no text from the body. A JSON library quotes the
 * token it could not parse into its message, Spring keeps that in the exception chain, and the chain reaches the error log, where
 * a bare secret matches no masking pattern (HI-0099). The parser's exception type stays; a transport failure beneath it comes out
 * as itself.
 */
final class UnquotedReadFailures {

    private UnquotedReadFailures() {
    }

    /**
     * Wraps every converter in the list, in place. Converters added afterwards are not covered.
     *
     * @param converters the client's converters
     */
    static void wrapAll(List<HttpMessageConverter<?>> converters) {
        converters.replaceAll(UnquotedReadFailures::wrap);
    }

    @SuppressWarnings("unchecked")
    private static HttpMessageConverter<?> wrap(HttpMessageConverter<?> converter) {
        if (converter instanceof SmartHttpMessageConverter<?> smart) {
            return new Smart((SmartHttpMessageConverter<Object>) smart);
        }
        if (converter instanceof GenericHttpMessageConverter<?> generic) {
            return new Generic((GenericHttpMessageConverter<Object>) generic);
        }
        return new Plain((HttpMessageConverter<Object>) converter);
    }

    private static HttpMessageNotReadableException unquoted(HttpMessageConversionException failure, HttpInputMessage message) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RemoteCallException remote) {
                throw remote;
            }
        }
        Throwable parser = failure.getCause() == null ? failure : failure.getCause();
        return new HttpMessageNotReadableException("The response body could not be parsed: " + parser.getClass().getName(), message);
    }

    private static class Plain implements HttpMessageConverter<Object> {

        private final HttpMessageConverter<Object> delegate;

        Plain(HttpMessageConverter<Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean canRead(Class<?> clazz, MediaType mediaType) {
            return delegate.canRead(clazz, mediaType);
        }

        @Override
        public boolean canWrite(Class<?> clazz, MediaType mediaType) {
            return delegate.canWrite(clazz, mediaType);
        }

        @Override
        public List<MediaType> getSupportedMediaTypes() {
            return delegate.getSupportedMediaTypes();
        }

        @Override
        public List<MediaType> getSupportedMediaTypes(Class<?> clazz) {
            return delegate.getSupportedMediaTypes(clazz);
        }

        @Override
        public Object read(Class<?> clazz, HttpInputMessage message) throws IOException {
            try {
                return delegate.read(clazz, message);
            } catch (HttpMessageConversionException e) {
                throw unquoted(e, message);
            }
        }

        @Override
        public void write(Object value, MediaType contentType, HttpOutputMessage message) throws IOException {
            delegate.write(value, contentType, message);
        }
    }

    private static final class Generic extends Plain implements GenericHttpMessageConverter<Object> {

        private final GenericHttpMessageConverter<Object> generic;

        Generic(GenericHttpMessageConverter<Object> delegate) {
            super(delegate);
            this.generic = delegate;
        }

        @Override
        public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
            return generic.canRead(type, contextClass, mediaType);
        }

        @Override
        public Object read(Type type, Class<?> contextClass, HttpInputMessage message) throws IOException {
            try {
                return generic.read(type, contextClass, message);
            } catch (HttpMessageConversionException e) {
                throw unquoted(e, message);
            }
        }

        @Override
        public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
            return generic.canWrite(type, clazz, mediaType);
        }

        @Override
        public void write(Object value, Type type, MediaType contentType, HttpOutputMessage message) throws IOException {
            generic.write(value, type, contentType, message);
        }
    }

    private static final class Smart extends Plain implements SmartHttpMessageConverter<Object> {

        private final SmartHttpMessageConverter<Object> smart;

        Smart(SmartHttpMessageConverter<Object> delegate) {
            super(delegate);
            this.smart = delegate;
        }

        @Override
        public boolean canRead(Class<?> clazz, MediaType mediaType) {
            return smart.canRead(clazz, mediaType);
        }

        @Override
        public boolean canWrite(Class<?> clazz, MediaType mediaType) {
            return smart.canWrite(clazz, mediaType);
        }

        @Override
        public boolean canRead(ResolvableType type, MediaType mediaType) {
            return smart.canRead(type, mediaType);
        }

        @Override
        public Object read(ResolvableType type, HttpInputMessage message, Map<String, Object> hints) throws IOException {
            try {
                return smart.read(type, message, hints);
            } catch (HttpMessageConversionException e) {
                throw unquoted(e, message);
            }
        }

        @Override
        public Object read(Class<?> clazz, HttpInputMessage message) throws IOException {
            return super.read(clazz, message);
        }

        @Override
        public boolean canWrite(ResolvableType targetType, Class<?> valueClass, MediaType mediaType) {
            return smart.canWrite(targetType, valueClass, mediaType);
        }

        @Override
        public void write(Object value, ResolvableType type, MediaType contentType, HttpOutputMessage message, Map<String, Object> hints)
                throws IOException {
            smart.write(value, type, contentType, message, hints);
        }

        @Override
        public void write(Object value, MediaType contentType, HttpOutputMessage message) throws IOException {
            super.write(value, contentType, message);
        }
    }
}
