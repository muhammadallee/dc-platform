/**
 * Composed JUnit 5 test annotations for platform services: {@link
 * ae.gov.dubaicustoms.platform.test.junit.PlatformTest} boots the whole platform with test defaults,
 * and the {@code Platform*Test} slices focus a context on one capability (messaging with a recording
 * transport, JPA on H2, or the web stack with {@code MockMvc}).
 *
 * <p>These types deliberately live one package below {@code ae.gov.dubaicustoms.platform.test} so the
 * platform's "api root packages carry contracts only" architecture rule does not apply to this Test
 * Support module.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.test.junit;
