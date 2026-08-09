package com.lynxis.orca.runtime.workitem.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import com.lynxis.orca.runtime.workitem.domain.PresenceTables.UserActivity;
import com.lynxis.orca.runtime.workitem.persistence.PresenceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Operator presence: the open {@code user_activity} row is the current status,
 * transitions close-and-open in one transaction, and the Push
 * path asks one question — <em>who is assignable right now?</em>
 *
 * <p>Assignable means exactly {@code IDLE} or {@code WORKING}; {@code DND},
 * {@code BREAK}, {@code OFFLINE} and {@code ACTIVE}
 * are not. An operator with no open row at all is {@code OFFLINE} — the state a
 * fresh installation's operators are in before their first transition.
 */
@Slf4j
public class PresenceService {

	private static final List<String> ASSIGNABLE = List.of(UserActivity.IDLE, UserActivity.WORKING);

	private final PresenceRepository repository;
	private final TransactionTemplate transactions;
	private final String siteExternalId;

	public PresenceService(PresenceRepository repository, TransactionTemplate transactions,
			String siteExternalId) {
		this.repository = repository;
		this.transactions = transactions;
		this.siteExternalId = siteExternalId;
	}

	/** The operator's current status — {@code OFFLINE} when no transition was ever recorded. */
	public String presenceOf(String userExternalId) {
		return repository.openRowOf(userExternalId)
				.map(UserActivity::status)
				.orElse(UserActivity.OFFLINE);
	}

	/**
	 * Moves the operator to a new status: close the open row, open the new one,
	 * one transaction. Two racing transitions serialise on the filtered unique
	 * index — the loser retries once on top of the winner's row, so the last
	 * writer wins and exactly one row stays open either way.
	 */
	public UserActivity setPresence(String userExternalId, String status) {
		try {
			return transition(userExternalId, status);
		}
		catch (DuplicateKeyException raced) {
			log.debug("presence transition for {} raced another; retrying on top of the winner",
					userExternalId);
			return transition(userExternalId, status);
		}
	}

	private UserActivity transition(String userExternalId, String status) {
		return transactions.execute(tx -> {
			Instant now = Instant.now();
			repository.closeOpenRow(userExternalId, now);
			repository.openRow(userExternalId, siteExternalId, status, now);
			return repository.openRowOf(userExternalId).orElseThrow(() -> new IllegalStateException(
					"the row this transaction just opened does not read back"));
		});
	}

	/** Presence history, newest first, for the {@code /operators/activity} duration view. */
	public List<UserActivity> historyOf(String userExternalId, int limit) {
		return repository.historyOf(userExternalId, limit);
	}

	/** Every assignable operator ({@code IDLE} or {@code WORKING} open row), longest-in-state first. */
	public List<UserActivity> assignableOperators() {
		return repository.openRowsWithStatus(ASSIGNABLE);
	}

	/**
	 * The Push selection: the first assignable operator among the eligible users,
	 * deterministically — {@code IDLE} before {@code WORKING} (an idle operator
	 * beats interrupting a working one), then longest-in-state first (the fairest
	 * queue), then the user id as the total-order tiebreak. One stated ordering,
	 * where 1.x iterated a Go map and got a different answer per run.
	 */
	public Optional<String> selectAssignable(Set<String> eligibleUsers) {
		if (eligibleUsers.isEmpty()) {
			return Optional.empty();
		}
		Set<String> eligible = new LinkedHashSet<>(eligibleUsers);
		return assignableOperators().stream()
				.filter(activity -> eligible.contains(activity.userExternalId()))
				.sorted(java.util.Comparator
						.comparing((UserActivity activity) ->
								UserActivity.IDLE.equals(activity.status()) ? 0 : 1)
						.thenComparing(UserActivity::startedAt)
						.thenComparing(UserActivity::userExternalId))
				.map(UserActivity::userExternalId)
				.findFirst();
	}
}
