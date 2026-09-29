package splusjava.net;

/** Creates transports; replaced in tests by an in-memory implementation. */
public interface TransportFactory {
    Transport create(String host, int port);
}
