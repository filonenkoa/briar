package org.briarproject.briar.android;

import org.briarproject.briar.BuildConfig;


public interface TestingConstants {

	/**
	 * Whether this is a debug build.
	 */
	boolean IS_DEBUG_BUILD = BuildConfig.DEBUG;

	/**
	 * Whether to prevent screenshots from being taken. Setting this to true
	 * prevents Recent Apps from storing screenshots of private information.
	 * Unfortunately this also prevents the user from taking screenshots
	 * intentionally.
	 */
	boolean PREVENT_SCREENSHOTS = !IS_DEBUG_BUILD;

	/**
	 * Fork — expiry disabled entirely.
	 */
	long EXPIRY_DATE = Long.MAX_VALUE;
}
