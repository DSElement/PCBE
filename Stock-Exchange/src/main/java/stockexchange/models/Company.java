package stockexchange.models;

public class Company {
    private final String stockSymbol;

    public Company(String stockSymbol){
        if (stockSymbol == null || stockSymbol.isBlank()){
            throw new IllegalArgumentException("Stock symbol cannot be null or blank");
        }
        this.stockSymbol = stockSymbol;
    }

    public String getStockSymbol() {
        return stockSymbol;
    }
}
