/**
 * The security capability contract: {@link ae.gov.dubaicustoms.platform.security.SecurityCustomizer}
 * extends the platform's baseline {@code HttpSecurity} chain, and
 * {@link ae.gov.dubaicustoms.platform.security.CurrentUser} /
 * {@link ae.gov.dubaicustoms.platform.security.CurrentUserAccessor} expose the authenticated
 * principal in a token-format-neutral shape.
 *
 * <p>{@code platform-security-autoconfigure} supplies the baseline JWT resource-server chain and
 * the {@code CurrentUserAccessor} implementation.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.security;
