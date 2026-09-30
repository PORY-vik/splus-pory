package splusjava;

import splusjava.tl.TLObject;

/** What {@link UpdatesTracker} needs to call {@code updates.getState}/{@code updates.getDifference}. */
interface RequestInvoker {
    Object invoke(TLObject request);
}
