package com.lynxis.orca.runtime.execution.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.platform.scope.ScopedSelect;

import lombok.RequiredArgsConstructor;

/**
 * The visit as a TREE, for the selector data provider: a row by its key or its
 * external identifier, and a row's children in key order. The provider walks
 * parent links to the root and the root's subtree for the same-visit sibling
 * lookup — the walk lives there, the row reads live here.
 *
 * <p>Deliberately read-only. Admission creates roots, the recorder creates
 * children, the completion path closes them; a repository that both walked the
 * tree and wrote it would be the beginning of a second lifecycle owner.
 */
@RequiredArgsConstructor
public class VisitTreeRepository {

	private static final String SCOPE_COLUMN = "site_external_id";

	private static final String[] COLUMNS = { "execution_id", "external_id", "site_external_id",
			"lane_id", "parent_execution_id", "status", "workflow_id", "started_at", "completed_at" };

	private final ScopeSeam seam;

	/** The visit row by its key, or empty — including when the caller's scope cannot see it. */
	public Optional<VisitNode> byId(long executionId) {
		return one(ScopedSelect.from("execution")
				.columns(COLUMNS)
				.scopedBy(SCOPE_COLUMN)
				.where("execution_id = ?", executionId));
	}

	/** The visit row by its stable external identifier. */
	public Optional<VisitNode> byExternalId(String externalId) {
		return one(ScopedSelect.from("execution")
				.columns(COLUMNS)
				.scopedBy(SCOPE_COLUMN)
				.where("external_id = ?", externalId));
	}

	/** A row's children, in key order — deterministic, so the sibling walk is too. */
	public List<VisitNode> childrenOf(long parentExecutionId) {
		return seam.select(ScopedSelect.from("execution")
						.columns(COLUMNS)
						.scopedBy(SCOPE_COLUMN)
						.where("parent_execution_id = ?", parentExecutionId)
						.orderBy("execution_id"),
				VisitTreeRepository::node);
	}

	private Optional<VisitNode> one(ScopedSelect select) {
		return seam.select(select, VisitTreeRepository::node).stream().findFirst();
	}

	private static VisitNode node(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		long laneId = rs.getLong("lane_id");
		Long parent = rs.getObject("parent_execution_id") == null ? null : rs.getLong("parent_execution_id");
		Long workflowId = rs.getObject("workflow_id") == null ? null : rs.getLong("workflow_id");
		return new VisitNode(rs.getLong("execution_id"), rs.getString("external_id"),
				rs.getString("site_external_id"), laneId, parent, rs.getString("status"),
				workflowId, rs.getTimestamp("started_at"), rs.getTimestamp("completed_at"));
	}

	/**
	 * One row of the visit tree. {@code workflowId} is null for visits of the
	 * hand-written gate process, which predates the compiler — absence is the
	 * honest value, not zero.
	 */
	public record VisitNode(long executionId, String externalId, String siteExternalId,
			long laneId, Long parentExecutionId, String status, Long workflowId,
			Timestamp startedAt, Timestamp completedAt) {
	}
}
