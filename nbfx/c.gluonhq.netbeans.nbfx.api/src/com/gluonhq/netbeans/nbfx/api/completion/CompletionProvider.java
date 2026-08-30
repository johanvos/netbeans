package com.gluonhq.netbeans.nbfx.api.completion;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Provider for editor completion queries.
 *
 * <p>When the user is typing new code or editing existing code, after pressing a shortcut command,
 * the editor will trigger a completion query to get a list of suitable proposals that can be displayed
 * to the user. After finding and selecting the correct one, if any, the code will be completed accordingly.
 * </p>
 */
public interface CompletionProvider {

    /** Standard completion query (primary Ctrl+Space behavior). */
    int COMPLETION_QUERY_TYPE = 1;
    /** Completion-all query (typically second Ctrl+Space to broaden suggestions). */
    int COMPLETION_ALL_QUERY_TYPE = 2;

    /**
     * Checks whether this provider can serve completion for the given context.
     *
     * @param context immutable completion request snapshot
     * @return {@code true} if this provider should participate in the query
     */
    boolean supports(CompletionContext context);

    /**
     * Computes completion items for the provided context.
     *
     * @param context immutable completion request snapshot
     * @param cancellation cancellation token for aborting stale work
     * @return async completion result with a list, that could be empty, of {@link CompletionItem items}
     * that can be displayed to the user as completion proposals
     */
    CompletableFuture<List<CompletionItem>> query(CompletionContext context,
                                                  CompletionCancellation cancellation);
}

