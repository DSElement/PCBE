package exchange.market;

import exchange.model.Company;
import java.time.Instant;

record Trade(long id, Company company, int price, long quantity, long buyer,
                     long seller, long buyOrder, long sellOrder, Instant time) {
    String wire() {
        return "TRADE " + id + " " + company + " " + price + " " + quantity + " " + buyer
                + " " + seller + " " + buyOrder + " " + sellOrder + " " + time;
    }
}
