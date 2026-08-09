package com.lynxis.orca.checks;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lynxis.orca.platform.scope.table.Growth;
import com.lynxis.orca.platform.scope.table.PersistentTable;
import com.lynxis.orca.platform.scope.table.RetentionClass;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Fails the build when a table declared as traffic-growing names no retention
 * class.
 *
 * <p>Every table that grows with traffic must name a retention class; this is the
 * build-time enforcement of that rule.
 *
 * <p>The failure this prevents is slow and quiet. A free-text class lets a typo
 * silently create a class no purge job serves, so those rows live forever; a
 * missing class does the same thing without the typo. Neither produces an error.
 * The first symptom is a disk alert at a customer site two years into a
 * deployment, by which point the data is too large to remove in a maintenance
 * window. That is how {@code execution} and {@code execution_context} were missed
 * in the current production system.
 *
 * <p><strong>What this check does not do, and why.</strong> It enforces that a
 * class is <em>named</em>; it does not enforce membership of the closed list.
 * Membership belongs in a database {@code CHECK} over a closed 18-value list. That
 * list lives in the ORCA Data Dictionary, which is not in this repository, and its
 * two published copies both claim to be complete but are not identical. Writing an
 * enum here would pick one contradictory list and publish the choice as settled,
 * so the gap is reported rather than filled.
 */
class RetentionClassRule {

	@Test
	@DisplayName("every traffic-growing table names a retention class")
	void trafficGrowingTablesDeclareARetentionClass() {
		classes()
				.that().areAnnotatedWith(PersistentTable.class)
				.should(nameARetentionClassWhenTrafficGrowing())
				.because("a table nobody classified is a table no purge job serves, so its rows live "
						+ "forever — and nothing says so until a disk fills at a customer site")
				.check(OrcaClasses.production());
	}

	@Test
	@DisplayName("every JPA entity also declares how it grows, so a new table cannot skip the question")
	void jpaEntitiesDeclareTheirGrowth() {
		// The rule was introduced before the repository had JPA entities. It landed
		// early because a check added after entities exist merely certifies whatever
		// was already written, while the first entity is the cheapest moment to be told.
		classes()
				.that().areAnnotatedWith("jakarta.persistence.Entity")
				.should().beAnnotatedWith(PersistentTable.class)
				.allowEmptyShould(true)
				.because("Every table that grows with traffic must declare its retention treatment, "
						+ "and a table that never declared its growth was never considered")
				.check(OrcaClasses.production());
	}

	@Test
	@DisplayName("the growth question has no default, so it cannot be skipped by omission")
	void growthHasNoDefault() throws Exception {
		// A default here would answer the question on the author's behalf, in
		// whichever direction was convenient. If somebody adds one to make a build
		// pass, this fails.
		assertThat(PersistentTable.class.getMethod("growth").getDefaultValue())
				.as("PersistentTable.growth() must have no default value")
				.isNull();
		assertThat(Growth.values())
				.containsExactly(Growth.TRAFFIC_GROWING, Growth.BOUNDED);
	}

	private static ArchCondition<JavaClass> nameARetentionClassWhenTrafficGrowing() {
		return new ArchCondition<>("name a retention class when traffic-growing") {
			@Override
			public void check(JavaClass item, ConditionEvents events) {
				PersistentTable table = item.getAnnotationOfType(PersistentTable.class);
				if (table.growth() != Growth.TRAFFIC_GROWING) {
					return;
				}
				if (!item.isAnnotatedWith(RetentionClass.class)) {
					events.add(SimpleConditionEvent.violated(item,
							item.getName() + " declares table '" + table.name()
									+ "' as TRAFFIC_GROWING but names no @RetentionClass. "
									+ "Rows that no purge job serves live forever."));
					return;
				}
				String retentionClass = item.getAnnotationOfType(RetentionClass.class).value();
				if (retentionClass == null || retentionClass.isBlank()) {
					events.add(SimpleConditionEvent.violated(item,
							item.getName() + " declares table '" + table.name()
									+ "' as TRAFFIC_GROWING with a blank @RetentionClass. "
									+ "A blank class is the same as no class, with a note attached."));
				}
			}
		};
	}
}
