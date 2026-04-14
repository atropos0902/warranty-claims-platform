package warranty.commons;

/** Bearer token a service uses when it calls another service. */
public interface TokenSource {
    String token();
}
