package ca.waltermiller.mcpapi.endpoints;

/**
 * Common shape of the core operation result records: a success flag plus an error
 * message when the operation failed. Lets generic plumbing (e.g. TaskExecutor)
 * handle any operation result uniformly.
 */
interface OperationResult {
    boolean success();

    String error();
}
