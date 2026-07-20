/**
 * The validation capability contract: common Bean Validation constraints every DC service needs —
 * {@link ae.gov.dubaicustoms.platform.validation.NotBlankTrimmed},
 * {@link ae.gov.dubaicustoms.platform.validation.Ulid},
 * {@link ae.gov.dubaicustoms.platform.validation.SafeText}, and
 * {@link ae.gov.dubaicustoms.platform.validation.FutureInstant}.
 *
 * <p>Validators live under {@code .internal}; message keys resolve through the contributor
 * bundle shipped in this jar, and {@code platform-validation-autoconfigure} layers the platform
 * message source on top.
 *
 * @since 0.1.0
 */
package ae.gov.dubaicustoms.platform.validation;
