/**
 * Transactional outbox and relay — the mechanism that replaces the message broker
 * deliberately absent from an on-site installation.
 *
 * <p>The business fact and its outbox row are written in one transaction, so a fact
 * cannot be published without being recorded nor recorded without being published.
 * A relay claims rows with a skip-locked read and offers each to every registered
 * consumer until acknowledged; a row is deletable only when all of them have.
 *
 * <p>This package knows about transactions, sequences, ordering keys and consumers.
 * It does not know what a visit, a lane, a ticket, a driver or a truck is.
 */
package com.lynxis.orca.platform.outbox;
