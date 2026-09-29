package splusjava;

/** Runs every test class: {@code java -cp classes:test-classes splusjava.AllTests}. */
public final class AllTests {
    private AllTests() {
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        TLTests.run();
        NetTests.run();
        CryptoTests.run();
        SrpTests.run();
        HighLevelTests.run();
        UpdatesTests.run();
        ChannelUpdatesTests.run();
        System.out.println();
        System.out.println("passed: " + T.passed + ", failed: " + T.failed + " (" + (System.currentTimeMillis() - t0) + " ms)");
        if (T.failed > 0) {
            System.exit(1);
        }
    }
}
