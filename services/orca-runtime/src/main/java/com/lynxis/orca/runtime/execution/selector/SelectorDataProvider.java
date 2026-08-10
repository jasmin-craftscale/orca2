package com.lynxis.orca.runtime.execution.selector;

import java.util.Map;

/**
 * The data the evaluator may ask its host for — a one-to-one mirror of the Go
 * {@code SelectorRepositoryInterface}, minus context. The evaluator itself stays pure: the
 * runtime binds this to ORCA tables, the designer's publish-time validation binds it to a
 * draft snapshot, and the conformance suite binds it to fixture-canned data.
 */
public interface SelectorDataProvider {

    Object fetchDataSets(String key, int workflowExecutionId) throws DtoErrorException;

    Object fetchNodeConfigurationDetails() throws DtoErrorException;

    Object fetchWorkflowExecutionData(String columnName, int workflowExecutionId) throws DtoErrorException;

    int getPrimaryKeyByUuid(String tableName, String idColumnName, String uuidColumnName,
            String uuidValue) throws GoErrorException;

    /** Go's {@code *string}: null means no configuration row. */
    String getResourceConfigurations(int resourceId, String resourceType, String configKey)
            throws DtoErrorException;

    /** The raw node-execution row; the evaluator reads {@code ExecutionPayload} from it. */
    Map<String, Object> fetchNodeExecution(int workflowId, String nodeUuid, int workflowExecutionId)
            throws DtoErrorException;

    int getPreviousWorkflowExecutionId(int workflowId, int workflowExecutionId) throws DtoErrorException;

    Object executeQuery(String query) throws DtoErrorException;

    String extractLanePropertiesByLaneCode(String laneCode, String field) throws DtoErrorException;

    String fetchDeviceUrlForLane(String laneCode, String deviceAlias, String column) throws DtoErrorException;

    WorkflowRef getWorkflowIdByAliasName(String workflowAliasName, int workflowExecutionId)
            throws DtoErrorException;

    String getNodeUuidByAliasName(int workflowId, String nodeAliasName) throws DtoErrorException;

    record WorkflowRef(int id, String uuid) {
    }
}
