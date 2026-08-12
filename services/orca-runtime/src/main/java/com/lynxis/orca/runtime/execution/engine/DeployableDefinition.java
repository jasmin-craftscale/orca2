package com.lynxis.orca.runtime.execution.engine;

/**
 * A definition an engine implementation can deploy. Engine-agnostic on purpose: the Flowable
 * adapter deploys compiled BPMN bytes; the in-memory fake deploys its own step list. What
 * every implementation shares is the identity contract below.
 */
public interface DeployableDefinition {

    /**
     * The definition key. The standing constraint here: the engine-side
     * definition id must round-trip as {@code key:version:uuid} inside 64 characters, so keys
     * stay short and derived from {@code workflow_id} ({@code proc_<workflowId>}) — never
     * from an ORCA workflow name (D1: 19 names collide).
     */
    String definitionKey();
}
