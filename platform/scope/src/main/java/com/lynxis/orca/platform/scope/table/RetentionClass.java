package com.lynxis.orca.platform.scope.table;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The retention class a traffic-growing table's rows belong to.
 *
 * <p><strong>The value is a String and not an enum, and that is a gap rather than
 * a design choice.</strong> §B10 says the class list "is closed and enumerated",
 * and §C2 invariant 4 says it is closed by a database {@code CHECK} over an
 * 18-value list. That list lives in the ORCA Data Dictionary, which is not in this
 * repository — and the open-questions register records, as a High documentation
 * item, that <em>the list appears in two documents, both declared closed, and they
 * are not identical</em>.
 *
 * <p>Writing an enum here would mean choosing between two lists that disagree and
 * publishing the choice as settled. So this carries the mechanism and not the
 * list: the build check enforces that a traffic-growing table <em>names</em> a
 * class, which is exactly what §B10 says the build-time check does. Membership of
 * the closed list is enforced by the database constraint, and that constraint
 * cannot be written until the two documents are reconciled.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RetentionClass {

	/** The class name. Must be non-blank; membership of the closed list is a database constraint. */
	String value();
}
