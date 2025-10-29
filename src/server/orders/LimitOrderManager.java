package server.orders;

import server.OrderManager.Order;
import server.orderbook.OrderBook;
import server.orderbook.MatchingEngine;

/*
 * Gestisce gli ordini Limit
 */
public class LimitOrderManager {

    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_RESET = "\u001B[0m";

    public static boolean insertLimitOrder(Order order, MatchingEngine.TradeExecutor tradeExecutor) {
        try {
            if (!"limit".equals(order.getOrderType())) {
                System.err.println(ANSI_RED + "[LimitOrderManager] Errore: Ordine non è di tipo 'limit'" + ANSI_RESET);
                return false;
            }

            if (!isValidLimitOrder(order)) {
                System.err.println(ANSI_RED + "[LimitOrderManager] Errore: Parametri non validi"+ ANSI_RESET);
                return false;
            }

            System.out.println(ANSI_GREEN + "[LimitOrderManager] Inserimento Limit Order " + order.getOrderId()+ ANSI_RESET);

            if ("bid".equals(order.getType())) {
                OrderBook.addToBidBook(order);
            } else {
                OrderBook.addToAskBook(order);
            }

            MatchingEngine.performLimitOrderMatching(tradeExecutor);

            return true;

        } catch (Exception e) {
            System.err.println(ANSI_RED + "[LimitOrderManager] Errore: " + e.getMessage()+ ANSI_RESET);
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