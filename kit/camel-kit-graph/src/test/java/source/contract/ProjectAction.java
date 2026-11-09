package source.contract;

/** A class named by hostile source fixtures, available so accidental class loading is observable. */
public final class ProjectAction {
    static {
        throwIfInitialized();
    }

    private static void throwIfInitialized() {
        throw new AssertionError("Graph parsing initialized a project class");
    }
}
