package server.orders;

import server.OrderManager.Order;
import server.orderbook.OrderBook;
import server.orderbook.MatchingEngine;

/*
 * Gestisce gli ordini Market
 */
public class MarketOrderManager {

    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_RESET = "\u001B[0m";

    public static boolean insertMarketOrder(Order order, MatchingEngine.TradeExecutor tradeExecutor) {
        try {
            if (!"market".equals(order.getOrderType())) {
                System.err.println(ANSI_RED + "[MarketOrderManager] Errore: Ordine non è di tipo 'market'" + ANSI_RESET);
                return false;
            }

            if (!isValidMarketOrder(order)) {
                System.err.println(ANSI_RED + "[MarketOrderManager] Errore: Parametri non validi" + ANSI_RESET);
                return false;
            }

            if (!hasLiquidity(order)) {
                System.err.println(ANSI_RED + "[MarketOrderManager] Errore: Liquidità insufficiente" + ANSI_RESET);
                return false;
            }

            System.out.println(ANSI_GREEN + "[MarketOrderManager] Esecuzione Market Order " + order.getOrderId() + ANSI_RESET);

            boolean success = MatchingEngine.executeMarketOrder(order, tradeExecutor);

            if (!success) {
                System.err.println(ANSI_RED + "[MarketOrderManager] Market Order non completamente eseguito - scartato" + ANSI_RESET);
                return false;
            }

            System.out.println(ANSI_GREEN + "[MarketOrderManager] Market Order eseguito completamente" + ANSI_RESET);
            return true;

        } catch (Exception e) {
            System.err.println(ANSI_RED + "[MarketOrderManager] Errore: " + e.getMessage() + ANSI_RESET);
            return false;
        }
    }

    private static boolean isValidMarketOrder(Order order) {
        if (!isValidOrderType(order.getType())) {
            return false;
        }

        if (order.getSize() <= 0) {
            return false;
        }

        if (order.getPrice() != 0) {
            System.err.println(ANSI_RED + "[MarketOrderManager] Market Order deve avere price = 0" + ANSI_RESET);
            return false;
        }

        return true;
    }

    private static boolean isValidOrderType(String type) {

        return "bid".equals(type) || "ask".equals(type);
    }

    private static boolean hasLiquidity(Order order) {

        return OrderBook.hasLiquidity(order.getType(), order.getSize());
    }
}