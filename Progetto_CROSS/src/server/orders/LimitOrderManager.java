package server.orders;

import server.OrderManager.Order;
import server.orderbook.OrderBook;
import server.orderbook.MatchingEngine;

/*
 * Gestisce gli ordini Limit
 */
public class LimitOrderManager {

    public static boolean insertLimitOrder(Order order, MatchingEngine.TradeExecutor tradeExecutor) {
        try {
            if (!"limit".equals(order.getOrderType())) {
                System.err.println("[LimitOrderManager] Errore: Ordine non è di tipo 'limit'");
                return false;
            }

            if (!isValidLimitOrder(order)) {
                System.err.println("[LimitOrderManager] Errore: Parametri non validi");
                return false;
            }

            System.out.println("[LimitOrderManager] Inserimento Limit Order " + order.getOrderId());

            if ("bid".equals(order.getType())) {
                OrderBook.addToBidBook(order);
            } else {
                OrderBook.addToAskBook(order);
            }

            MatchingEngine.performLimitOrderMatching(tradeExecutor);

            return true;

        } catch (Exception e) {
            System.err.println("[LimitOrderManager] Errore: " + e.getMessage());
            return false;
        }
    }

    private static boolean isValidLimitOrder(Order order) {
        return isValidOrderType(order.getType()) && order.getSize() > 0 && order.getPrice() > 0;
    }

    private static boolean isValidOrderType(String type) {
        return "bid".equals(type) || "ask".equals(type);
    }
}