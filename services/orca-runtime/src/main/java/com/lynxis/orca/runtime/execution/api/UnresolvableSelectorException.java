package com.lynxis.orca.runtime.execution.api;

/**
 * A selector naming something that cannot exist — an unknown workflow, an unknown visit, a
 * column off the visit row that is not there. <b>Not</b> a value that simply has not been
 * written yet; that is legitimately empty and stays empty.
 *
 * <p>The platform draws no such line: every one of these answers {@code ""}, a condition then
 * compares {@code "" == "LOADED"}, takes the other branch, and a truck goes the wrong way with
 * nothing logged anywhere. It is the largest silent-failure class in the estate and the reason
 * two defects in this rewrite's own provider survived a green test suite.
 *
 * <p>So it fails, loudly, and the visit stops. That is a deliberate change of result in
 * exactly the cases where today's result is already wrong. The cost is paid at publish time
 * rather than at 3am: the designer validates selectors before a workflow deploys, which makes
 * this a backstop nobody should reach rather than a new operational burden.
 */
public final class UnresolvableSelectorException extends RuntimeException {

    private final String selectorSubject;

    public UnresolvableSelectorException(String selectorSubject, String detail) {
        super("unresolvable selector: " + detail
                + " — a selector naming something that cannot exist stops the visit rather "
                + "than reading empty and branching on it");
        this.selectorSubject = selectorSubject;
    }

    /** What could not be resolved — the uuid, alias or column the selector named. */
    public String selectorSubject() {
        return selectorSubject;
    }
}
