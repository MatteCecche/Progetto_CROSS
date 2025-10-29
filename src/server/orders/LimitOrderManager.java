package server.orders;

import server.OrderManager.Order;
import server.orderbook.OrderBook;
import server.orderbook.MatchingEngine;
import server.utility.Colors;

public class LimitOrderManager {

    public static boolean insertLimitOrder(Order order, MatchingEngine.TradeExecutor tradeExecutor) {
        try {
            if (!"limit".equals(order.getOrderType())) {
                System.err.println(Colors.RED + "[LimitOrderManager] Errore: Ordine non è di tipo 'limit'" + Colors.RESET);
                return false;
            }

            if (!isValidLimitOrder(order)) {
                System.err.println(Colors.RED + "[LimitOrderManager] Errore: Parametri non validi"+ Colors.RESET);
                return false;
            }

            System.out.println(Colors.GREEN + "[LimitOrderManager] Inserimento Limit Order " + order.getOrderId()+ Colors.RESET);

            if ("bid".equals(order.getType())) {
                OrderBook.addToBidBook(order);
            } else {
                OrderBook.addToAskBook(order);
            }

            MatchingEngine.performLimitOrderMatching(tradeExecutor);

            return true;

        } catch (Exception e) {
            System.err.println(Colors.RED + "[LimitOrderManager] Errore: " + e.getMessage()+ Colors.RESET);
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