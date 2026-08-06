package com.lynxis.orca.platform.web;

/**
 * Pagination metadata, carried by the envelope rather than by headers or by each
 * service's own invention.
 *
 * @param page       zero-based page index
 * @param size       page size actually applied, which may be smaller than the one asked for
 * @param totalItems total matching items <em>within the caller's scope</em> — a
 *                   count that escaped its scope would leak the size of what the
 *                   caller cannot see
 * @param totalPages derived, and carried so a client does not have to compute it
 */
public record PageMeta(int page, int size, long totalItems, int totalPages) {

	public static PageMeta of(int page, int size, long totalItems) {
		int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalItems / size);
		return new PageMeta(page, size, totalItems, totalPages);
	}
}
