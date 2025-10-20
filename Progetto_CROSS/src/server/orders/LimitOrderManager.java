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
                System.err.println("[LimitOrderManager] Ordine non è di tipo 'limit'");
                return false;
            }

            if (!isValidLimitOrder(order)) {
                System.err.println("[LimitOrderManager] Parametri non validi");
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
        if (!isValidOrderType(order.getType())) {
            return false;
        }

        if (order.getSize() <= 0) {
            return false;
        }

        if (order.getPrice() <= 0) {
            return false;
        }

        return true;
    }

    private static boolean isValidOrderType(String type) {
        return "bid".equals(type) || "ask".equals(type);
    }
}