package exchange.model;


/** Expected command rejection, distinct from networking or programming failures. */
public final class MarketException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final String code;

    public MarketException(String code, String message) {
        super(message);
        this.code = code;
    }
}
