package com.lynxis.orca.runtime.execution.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynxis.orca.runtime.execution.api.dto.SelectorNamespace;
import org.junit.jupiter.api.Test;

/**
 * The ranking half of autocomplete: which steps have already run at the editing point.
 *
 * <p>A selector naming a step downstream resolves to empty at runtime, the condition compares
 * against empty, and the truck takes the other branch silently — so the ones already run are
 * what a builder offers first. <b>Offered first, not exclusively:</b> everything stays
 * available, because a static graph cannot prove every legitimate reference and an editor that
 * hides options is one authors route around.
 */
class DesignerGraphTest {

    /** START → scan → decision → (left | right); left and right cannot see each other. */
    private static String branchingDraft() {
        return WorkflowJson.workflow(500)
                .node("s", "START", "Start")
                .ioNode("scan", "Scan", "INPUT", "T")
                .node("d", "DECISION", "Empty?")
                .node("left", "DISPLAY", "Say empty")
                .node("right", "DISPLAY", "Say loaded")
                .node("e", "TERMINATOR", "End")
                .link("s", "scan").link("scan", "d")
                .branch("b1", "d", "empty", 1, WorkflowJson.simpleCondition("$.workflow.dataset.load", "==", "EMPTY"))
                .branch("b2", "d", "loaded", 2, null)
                .link("b1", "left").link("b2", "right")
                .link("left", "e").link("right", "e")
                .json();
    }

    @Test
    void stepsThatHaveAlreadyRunAreMarkedAndTheRestAreStillOffered() {
        var nodes = DesignerGraph.nodesFor(branchingDraft(), "left");

        assertThat(nodes).extracting(SelectorNamespace.NodeRef::uuid)
                .as("nothing is hidden — the builder ranks, it does not police")
                .containsExactlyInAnyOrder("s", "scan", "d", "left", "right", "e");
        assertThat(nodes).filteredOn(SelectorNamespace.NodeRef::runsBefore)
                .extracting(SelectorNamespace.NodeRef::uuid)
                .as("everything on the path to this step")
                .containsExactlyInAnyOrder("s", "scan", "d");
        assertThat(nodes).filteredOn(n -> !n.runsBefore())
                .extracting(SelectorNamespace.NodeRef::uuid)
                .as("the sibling branch and the end are available but not ranked first")
                .containsExactlyInAnyOrder("right", "e", "left");
    }

    @Test
    void withNoEditingPointEverythingCounts() {
        var nodes = DesignerGraph.nodesFor(branchingDraft(), null);

        assertThat(nodes).allMatch(SelectorNamespace.NodeRef::runsBefore);
    }

    @Test
    void ancestryFollowsBranchesRatherThanTheCanvas() {
        // 'left' hangs off a BRANCH via decision_uuid — there is no link row from the decision
        // to it. Reading only links makes every branched step look unreachable and ancestry
        // stops at the first decision, which is most workflows.
        var nodes = DesignerGraph.nodesFor(branchingDraft(), "e");

        assertThat(nodes).filteredOn(SelectorNamespace.NodeRef::runsBefore)
                .extracting(SelectorNamespace.NodeRef::uuid)
                .contains("left", "right", "d", "scan", "s");
    }

    @Test
    void theDraftsOwnConditionsAreVocabulary() {
        assertThat(DesignerGraph.authoredDatasetKeys(branchingDraft()))
                .as("a key this workflow already reads is one an author will read again")
                .contains("load");
    }
}
