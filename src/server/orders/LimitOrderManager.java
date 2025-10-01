package server.orders;

import server.OrderManager.Order;
import server.orderbook.OrderBook;
import server.orderbook.MatchingEngine;
import server.utility.PriceCalculator;

/**
 * Gestisce gli ordini Limit nel sistema CROSS
 * - Validazione parametri Limit Order
 * - Inserimento nell'OrderBook appropriato (bid/ask)
 * - Coordinamento con MatchingEngine per matching immediato
 */
public class LimitOrderManager {

    /**
     * Inserisce un Limit Order nel sistema
     *
     * @param order L'ordine Limit da inserire
     * @param tradeExecutor Callback per esecuzione trade
     * @return true se inserimento avvenuto con successo, false altrimenti
     */
    public static boolean insertLimitOrder(Order order, MatchingEngine.TradeExecutor tradeExecutor) {
        try {
            // Validazione tipo ordine
            if (!"limit".equals(order.getOrderType())) {
                System.err.println("[LimitOrderManager] Ordine non è di tipo 'limit'");
                return false;
            }

            // Validazione parametri
            if (!isValidLimitOrder(order)) {
                System.err.println("[LimitOrderManager] Parametri Limit Order non validi: type=" +
                        order.getType() + ", size=" + order.getSize() + ", price=" + order.getPrice());
                return false;
            }

            System.out.println("[LimitOrderManager] Inserimento Limit Order " + order.getOrderId() +
                    " - " + order.getUsername() + " " + order.getType() + " " +
                    PriceCalculator.formatSize(order.getSize()) + " BTC @ " +
                    PriceCalculator.formatPrice(order.getPrice()) + " USD");

            // Inserimento nell'order book appropriato
            if ("bid".equals(order.getType())) {
                OrderBook.addToBidBook(order);
            } else {
                OrderBook.addToAskBook(order);
            }

            // Tentativo di matching immediato
            MatchingEngine.performLimitOrderMatching(tradeExecutor);

            return true;

        } catch (Exception e) {
            System.err.println("[LimitOrderManager] Errore inserimento Limit Order: " + e.getMessage());
            return false;
        }
    }

    /**
     * Valida i parametri di un Limit Order
     *
     * @param order L'ordine da validare
     * @return true se l'ordine è valido, false altrimenti
     */
    private static boolean isValidLimitOrder(Order order) {
        // Validazione tipo (bid o ask)
        if (!isValidOrderType(order.getType())) {
            return false;
        }

        // Validazione size > 0
        if (order.getSize() <= 0) {
            return false;
        }

        // Validazione price > 0 (caratteristica specifica dei Limit Orders)
        if (order.getPrice() <= 0) {
            return false;
        }

        return true;
    }

    /**
     * Verifica che il tipo di ordine sia valido (bid o ask)
     */
    private static boolean isValidOrderType(String type) {
        return "bid".equals(type) || "ask".equals(type);
    }

    /**
     * Ottiene statistiche sui Limit Orders nell'OrderBook
     *
     * @return Mappa con statistiche correnti
     */
    public static java.util.Map<String, Object> getLimitOrderStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();

        Integer bestBid = OrderBook.getBestBidPrice();
        Integer bestAsk = OrderBook.getBestAskPrice();

        stats.put("bestBidPrice", bestBid);
        stats.put("bestAskPrice", bestAsk);

        if (bestBid != null && bestAsk != null) {
            stats.put("spread", bestAsk - bestBid);
        } else {
            stats.put("spread", null);
        }

        stats.put("bidLevels", OrderBook.getBidOrdersMap().size());
        stats.put("askLevels", OrderBook.getAskOrdersMap().size());

        return stats;
    }
}