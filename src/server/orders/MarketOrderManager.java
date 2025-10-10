package server.orders;

import server.OrderManager.Order;
import server.orderbook.OrderBook;
import server.orderbook.MatchingEngine;
import server.utility.PriceCalculator;

/**
 * Gestisce gli ordini Market nel sistema CROSS
 * - Validazione parametri Market Order
 * - Controllo disponibilità liquidità nell'order book
 * - Esecuzione immediata tramite MatchingEngine
 */
public class MarketOrderManager {

    //Inserisce ed esegue un Market Order nel sistema
    public static boolean insertMarketOrder(Order order, MatchingEngine.TradeExecutor tradeExecutor) {
        try {
            // Validazione tipo ordine
            if (!"market".equals(order.getOrderType())) {
                System.err.println("[MarketOrderManager] Ordine non è di tipo 'market'");
                return false;
            }

            // Validazione parametri
            if (!isValidMarketOrder(order)) {
                System.err.println("[MarketOrderManager] Parametri Market Order non validi: type=" +
                        order.getType() + ", size=" + order.getSize());
                return false;
            }

            // Controllo disponibilità liquidità nel lato opposto dell'order book
            if (!hasLiquidity(order)) {
                System.err.println("[MarketOrderManager] Market Order rifiutato: liquidità insufficiente per " +
                        PriceCalculator.formatSize(order.getSize()) + " BTC " + order.getType());
                return false;
            }

            System.out.println("[MarketOrderManager] Esecuzione Market Order " + order.getOrderId() +
                    " - " + order.getUsername() + " " + order.getType() + " " +
                    PriceCalculator.formatSize(order.getSize()) + " BTC al prezzo di mercato");

            // Esecuzione immediata contro order book
            boolean success = MatchingEngine.executeMarketOrder(order, tradeExecutor);

            if (success) {
                System.out.println("[MarketOrderManager] Market Order " + order.getOrderId() +
                        " eseguito completamente");
            } else {
                System.out.println("[MarketOrderManager] Market Order " + order.getOrderId() +
                        " eseguito parzialmente");
            }

            return true;

        } catch (Exception e) {
            System.err.println("[MarketOrderManager] Errore inserimento Market Order: " + e.getMessage());
            return false;
        }
    }

    //Valida i parametri di un Market Order
    private static boolean isValidMarketOrder(Order order) {
        // Validazione tipo (bid o ask)
        if (!isValidOrderType(order.getType())) {
            return false;
        }

        // Validazione size > 0
        if (order.getSize() <= 0) {
            return false;
        }

        // Market Orders devono avere price = 0 (no prezzo limite)
        if (order.getPrice() != 0) {
            System.err.println("[MarketOrderManager] Market Order deve avere price = 0");
            return false;
        }

        return true;
    }

    //Verifica che il tipo di ordine sia valido (bid o ask)
    private static boolean isValidOrderType(String type) {
        return "bid".equals(type) || "ask".equals(type);
    }

    //Verifica se c'è liquidità sufficiente nell'order book per eseguire il Market Order
    private static boolean hasLiquidity(Order order) {
        String type = order.getType();
        int requiredSize = order.getSize();

        // Verifica usando il metodo di OrderBook
        return OrderBook.hasLiquidity(type, requiredSize);
    }

    //Ottiene statistiche sui Market Orders potenzialmente eseguibili
    public static java.util.Map<String, Object> getMarketOrderStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();

        // Calcola liquidità totale disponibile per Market BUY (sul lato ASK)
        int totalAskLiquidity = OrderBook.getAskOrdersMap().values().stream()
                .flatMap(list -> list.stream())
                .mapToInt(Order::getRemainingSize)
                .sum();

        // Calcola liquidità totale disponibile per Market SELL (sul lato BID)
        int totalBidLiquidity = OrderBook.getBidOrdersMap().values().stream()
                .flatMap(list -> list.stream())
                .mapToInt(Order::getRemainingSize)
                .sum();

        stats.put("availableLiquidityForBuy", totalAskLiquidity);
        stats.put("availableLiquidityForSell", totalBidLiquidity);
        stats.put("bestBuyPrice", OrderBook.getBestAskPrice());
        stats.put("bestSellPrice", OrderBook.getBestBidPrice());

        return stats;
    }
}