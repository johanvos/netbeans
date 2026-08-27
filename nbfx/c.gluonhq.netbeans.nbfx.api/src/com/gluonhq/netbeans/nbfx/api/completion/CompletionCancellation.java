package com.gluonhq.netbeans.nbfx.api.completion;

/**
 * Cancellation token passed to providers so stale queries can be abandoned.
 */
@FunctionalInterface
public interface CompletionCancellation {

    /**
     * @return {@code true} if the query is stale or cancelled by the caller
     */
    boolean isCancelled();
}

