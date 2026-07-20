/**
 * The REST client capability contract: {@link ae.gov.dubaicustoms.platform.restclient.PlatformRestClientFactory}
 * hands out platform-conventional {@link org.springframework.web.client.RestClient.Builder}s,
 * failures surface as {@link ae.gov.dubaicustoms.platform.restclient.RemoteCallException}, and
 * {@link ae.gov.dubaicustoms.platform.restclient.PlatformRestClientCustomizer} is the extension
 * point for adjusting a named client's builder.
 *
 * <p>Applications inject the factory instead of {@code RestClient.Builder} directly;
 * {@code platform-restclient-autoconfigure} supplies the implementation.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.restclient;
