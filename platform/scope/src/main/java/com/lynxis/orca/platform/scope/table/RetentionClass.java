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
 * a design choice.</strong> The catalog is supposed to be a closed 18-value set
 * enforced by a database {@code CHECK}. It lives in the ORCA Data Dictionary,
 * which is not in this repository, and its two published copies both claim to be
 * complete but are not identical.
 *
 * <p>Writing an enum here would mean choosing between two lists that disagree and
 * publishing the choice as settled. So this carries the mechanism and not the
 * list: {@code RetentionClassRule} fails the build unless a traffic-growing table
 * <em>names</em> a class. Membership of the closed list belongs in the database
 * constraint, and that constraint cannot be written until the two source documents
 * are reconciled.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RetentionClass {

	/** The class name. Must be non-blank; membership of the closed list is a database constraint. */
	String value();
}
