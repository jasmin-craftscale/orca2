package com.lynxis.orca.platform.scope.table;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that this type describes a database table, and how that table grows.
 *
 * <p>{@link #growth()} has no default. Whoever adds a table has to answer the
 * question, and the build check reads the answer: a
 * {@link Growth#TRAFFIC_GROWING} table with no {@link RetentionClass} fails the
 * build.
 *
 * <p>The failure this prevents is slow and quiet. A table nobody classified is a
 * table no purge job serves, so its rows live forever — and the first symptom is a
 * disk alert at a customer site two years into a deployment, by which point the
 * data is too large to remove in a maintenance window.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PersistentTable {

	/** The table's name in the owning service's schema. */
	String name();

	/** Whether this grows with traffic. No default: the author decides, on the record. */
	Growth growth();
}
